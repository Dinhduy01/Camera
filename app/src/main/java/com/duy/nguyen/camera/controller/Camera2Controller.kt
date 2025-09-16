package com.duy.nguyen.camera.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.duy.nguyen.camera.model.CameraViewModel
import com.duy.nguyen.camera.util.createVideoUri
import com.duy.nguyen.camera.util.saveJpegToAppDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs

class Camera2Controller(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    // ---- State ----
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraId: String? = null
    private val isSwitching = AtomicBoolean(false)
    private var currentFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var flashOn: Boolean = false
    var currentAspectPhoto: CameraViewModel.Aspect = CameraViewModel.Aspect.RATIO_3_4
    var currentAspectVideo: CameraViewModel.Aspect = CameraViewModel.Aspect.RATIO_16_9

    private var previewSize: Size = Size(4000, 3000)
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    var isRecord: Boolean = false
    private var recordingUri: Uri? = null

    // Supported sizes (cập nhật theo camera hiện tại)
    private var supportedPreviewSizes: Array<Size> = emptyArray()
    private var supportedJpegSizes: Array<Size> = emptyArray()
    private var supportedVideoSizes: Array<Size> = emptyArray()

    // coroutines
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastReconfigAt: Long = 0

    // ---- Expose preview buffer size cho UI ----
    fun getPreviewBufferSize(): Size = previewSize

    // ---- Aspect helpers ----
    private fun aspectToFloat(a: CameraViewModel.Aspect) = when (a) {
        CameraViewModel.Aspect.RATIO_16_9 -> 16f / 9f
        CameraViewModel.Aspect.RATIO_3_4  -> 4f / 3f
        CameraViewModel.Aspect.RATIO_1_1  -> 1f
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        targetRatio: Float,
        epsilon: Float = 0.01f
    ): Size {
        if (choices.isEmpty()) return Size(0, 0)

        var bestExact: Size? = null
        var bestExactArea = 0L

        for (s in choices) {
            val ratio = s.width.toFloat() / s.height
            if (abs(ratio - targetRatio) < epsilon) {
                val area = s.width.toLong() * s.height.toLong()
                if (area > bestExactArea) {
                    bestExactArea = area
                    bestExact = s
                }
            }
        }
        Log.e("duy.nguyen2", "chooseOptimalSize: "+ bestExact )
        if (bestExact != null) return bestExact!!

        // Không có exact: chọn size có độ lệch tỉ lệ nhỏ nhất, tie-break theo diện tích
        var best = choices[0]
        var bestDiff = Float.MAX_VALUE
        var bestArea2 = 0L
        for (s in choices) {
            val ratio = s.width.toFloat() / s.height
            val diff = abs(ratio - targetRatio)
            val area = s.width.toLong() * s.height.toLong()
            if (diff < bestDiff - 1e-6 || (abs(diff - bestDiff) < 1e-6 && area > bestArea2)) {
                best = s
                bestDiff = diff
                bestArea2 = area
            }
        }
        return best
    }

    // ---- Public API ----
    fun attachPreview(view: TextureView) {
        textureView = view
        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                previewSurface = Surface(surface)
                scope.launch { startPreview { /* ready */ } }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface = null; return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    suspend fun resume(): Unit = withContext(Dispatchers.Default) {
        startPreview { _ -> }
    }

    suspend fun pause() = withContext(Dispatchers.Default) {
        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    suspend fun stop() = withContext(Dispatchers.Default) {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close(); imageReader = null
        mediaRecorder?.let {
            try { it.stop() } catch(_:Exception) {}
            try { it.reset() } catch(_:Exception) {}
            try { it.release() } catch(_:Exception) {}
        }
        mediaRecorder = null
        recorderSurface?.release(); recorderSurface = null
        try { scope.coroutineContext.cancelChildren() } catch(_:Throwable){}
        stopBackgroundThread()
    }

    suspend fun switchCamera() = withContext(Dispatchers.Default) {
        if (!isSwitching.compareAndSet(false, true)) return@withContext
        try {
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            try { captureSession?.close() } catch (_: Throwable) {}
            captureSession = null
            try { cameraDevice?.close() } catch (_: Throwable) {}
            cameraDevice = null
            imageReader?.close(); imageReader = null

            currentFacing =
                if (currentFacing == CameraCharacteristics.LENS_FACING_BACK)
                    CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

            chooseCamera()
            val desiredAspect = if (isRecord) currentAspectVideo else currentAspectPhoto
            previewSize = chooseOptimalSize(supportedPreviewSizes, aspectToFloat(desiredAspect))
            startPreview { }
        } finally { isSwitching.set(false) }
    }

    suspend fun setFlash(enabled: Boolean) = withContext(Dispatchers.Default) {
        flashOn = enabled
        previewRequestBuilder?.apply {
            set(CaptureRequest.FLASH_MODE,
                if (isRecord && flashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            try { captureSession?.setRepeatingRequest(build(), null, backgroundHandler) } catch (_: Throwable) {}
        }
    }

    suspend fun restartPreviewWithAspect(
        aspect: CameraViewModel.Aspect,
        onReady: ((Boolean) -> Unit)? = null
    ) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        if (now - lastReconfigAt < 180) return@withContext
        lastReconfigAt = now

        // Cập nhật aspect theo mode hiện tại
        if (isRecord) currentAspectVideo = aspect else currentAspectPhoto = aspect

        // 1) Dừng preview/session/device hiện tại
        try { pause() } catch (_: Throwable) {}

        // 2) Start lại preview với buffer/size mới → tránh méo khi vừa đổi ratio
        startPreview { ready -> onReady?.invoke(ready) }
    }
    fun isFrontCamera(): Boolean =
        currentFacing == CameraCharacteristics.LENS_FACING_FRONT

    suspend fun captureStill() = withContext(Dispatchers.Default) {
        val device = cameraDevice ?: return@withContext
        val session = captureSession ?: return@withContext
        val jpegSurface = imageReader?.surface ?: return@withContext
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                if (flashOn) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            session.captureBurst(listOf(builder.build()), null, backgroundHandler)

            previewRequestBuilder?.build()?.let {
                session.setRepeatingRequest(it, null, backgroundHandler)
            }
        } catch (_: Throwable) {}
    }

    suspend fun startRecording() = withContext(Dispatchers.Default) {
        val device = cameraDevice ?: throw IllegalStateException("Camera chưa mở")
        val st = textureView?.surfaceTexture ?: throw IllegalStateException("SurfaceTexture null")

        val ratio = aspectToFloat(currentAspectVideo)
        if (supportedPreviewSizes.isEmpty() || supportedVideoSizes.isEmpty()) chooseCamera()

        // 1) Preview size theo aspect video (TextureView vẫn full-screen; UI sẽ center-crop)
        val pvSize = chooseOptimalSize(supportedPreviewSizes, ratio)
        previewSize = pvSize
        st.setDefaultBufferSize(pvSize.width, pvSize.height)

        // 2) Recreate preview surface sau khi đổi buffer
        previewSurface?.release()
        previewSurface = Surface(st)

        // 3) MediaRecorder size theo video supported
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uri = createVideoUri(context, "VID_$ts")
            ?: throw IllegalStateException("Không tạo được URI video")
        recordingUri = uri
        val pfd = context.contentResolver.openFileDescriptor(uri, "w")

        val mr = MediaRecorder().also { mediaRecorder = it }
        val vSize = chooseOptimalSize(supportedVideoSizes, ratio)
        mr.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd!!.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(10_000_000)
            setVideoFrameRate(30)
            setOrientationHint(orientationDegrees())
            setVideoSize(vSize.width, vSize.height)
            prepare()
        }
        recorderSurface = mr.surface

        // 4) Session RECORD (preview + recorder)
        recreateSession(
            device,
            listOf(previewSurface!!, mr.surface),
            template = CameraDevice.TEMPLATE_RECORD
        ) { builder ->
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            try { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) } catch(_:Throwable){}
            try { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30,30)) } catch(_:Throwable){}
        }
        mr.start()
    }

    suspend fun stopRecording() = withContext(Dispatchers.Default) {
        val mr = mediaRecorder
        if (mr != null) {
            try { mr.stop() } catch (_: Exception) {}
            try { mr.reset() } catch (_: Exception) {}
            try { mr.release() } catch (_: Exception) {}
        }
        mediaRecorder = null
        recorderSurface?.release(); recorderSurface = null

        val device = cameraDevice ?: return@withContext
        val pSurface = previewSurface ?: return@withContext
        recreateSession(device, listOf(pSurface)) { builder ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    // ---- Open camera / sessions ----
    suspend fun startPreview(onReady: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        if (cameraDevice != null && captureSession != null && previewSurface != null) {
            resume(); onReady(true); return@withContext
        }
        if (previewSurface == null && textureView?.isAvailable == true) {
            previewSurface = Surface(textureView!!.surfaceTexture)
        }
        if (previewSurface == null) { onReady(false); return@withContext }

        startBackgroundThread()
        chooseCamera()
        val id = cameraId ?: return@withContext onReady(false)

        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) return@withContext onReady(false)
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createPreviewSession(onReady)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; onReady(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; onReady(false)
                }
            }, backgroundHandler)
        } catch (_: SecurityException) {
            cameraOpenCloseLock.release(); onReady(false)
        }
    }

    private fun createPreviewSession(onReady: (Boolean) -> Unit) {
        val device = cameraDevice ?: return onReady(false)
        val surfaceTexture = textureView?.surfaceTexture ?: return onReady(false)

        val desiredAspect = if (isRecord) currentAspectVideo else currentAspectPhoto
        val ratio = aspectToFloat(desiredAspect)

        if (supportedPreviewSizes.isEmpty() || (!isRecord && supportedJpegSizes.isEmpty())) {
            chooseCamera()
        }

        // Preview size theo aspect (TextureView vẫn full; UI center-crop)
        previewSize = chooseOptimalSize(supportedPreviewSizes, ratio)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)

        val surface = Surface(surfaceTexture)
        previewSurface = surface

        imageReader?.close(); imageReader = null
        val targets = mutableListOf(surface)
        if (!isRecord) {
            val jpegSize = chooseOptimalSize(supportedJpegSizes, aspectToFloat(currentAspectPhoto))
            imageReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    try { saveJpegToAppDir(context, image) } finally { image.close() }
                }, backgroundHandler)
            }
            targets += imageReader!!.surface
        }

        try {
            previewRequestBuilder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(
                        CaptureRequest.FLASH_MODE,
                        if (isRecord && flashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                    )
                }

            device.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                            onReady(true)
                        } catch (_: Exception) { onReady(false) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) = onReady(false)
                },
                backgroundHandler
            )
        } catch (_: Exception) {
            onReady(false)
        }
    }

    private suspend fun recreateSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        template: Int = CameraDevice.TEMPLATE_PREVIEW,
        configure: (CaptureRequest.Builder) -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val builder = device.createCaptureRequest(template)
                        surfaces.forEach { builder.addTarget(it) }
                        configure(builder)
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (_: Exception) { /* ignore */ }
                        cont.resume(Unit)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resume(Unit)
                    }
                },
                backgroundHandler
            )
        } catch (_: Exception) {
            cont.resume(Unit)
        }
    }

    // ---- Orientation / threads / camera pick ----
    @SuppressLint("ServiceCast")
    private fun orientationDegrees(): Int {
        val id = cameraId ?: return 0
        val chars = cameraManager.getCameraCharacteristics(id)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK

        val rotation = (context.display?.rotation)
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor - deviceDegrees + 360) % 360
        } else {
            (sensor + deviceDegrees) % 360
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun chooseCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == currentFacing) {
                cameraId = id

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
                supportedPreviewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                supportedJpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                supportedVideoSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

                Log.d("duy.nguyen2", "Supported PREVIEW: ${supportedPreviewSizes.joinToString { "${it.width}x${it.height}" }}")
                Log.d("duy.nguyen2", "Supported JPEG: ${supportedJpegSizes.joinToString { "${it.width}x${it.height}" }}")
                Log.d("duy.nguyen2", "Supported VIDEO: ${supportedVideoSizes.joinToString { "${it.width}x${it.height}" }}")
                return
            }
        }
        cameraId = cameraManager.cameraIdList.first()
    }
}
