package com.duy.nguyen.camera.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class Camera2Controller(
    private val context: Context, private val cameraManager: CameraManager
) {
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraId: String? = null
    private val isSwitching = AtomicBoolean(false)
    private var currentFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var textureView: TextureView? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var flashOn: Boolean = false
    private var currentAspect: CameraViewModel.Aspect = CameraViewModel.Aspect.RATIO_3_4
    private var previewSize: Size = Size(4000, 3000)
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    var isRecord = false
    private var recordingUri: Uri? = null

    fun attachPreview(view: TextureView) {
        textureView = view
        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
                previewSurface = Surface(surface)
                GlobalScope.launch {
                    startPreview { ready -> Log.d("Camera2Controller", "Preview ready=$ready") }
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface = null; return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    suspend fun startPreview(onReady: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        if (cameraDevice != null && captureSession != null && previewSurface != null) {
            resume(); onReady(true); return@withContext
        }
        if (previewSurface == null && textureView?.isAvailable == true) {
            previewSurface = Surface(textureView!!.surfaceTexture)
        }
        if (previewSurface == null) {
            onReady(false); return@withContext
        }

        startBackgroundThread()
        chooseCamera()
        val id = cameraId ?: return@withContext onReady(false)

        if (!cameraOpenCloseLock.tryAcquire(
                2500, TimeUnit.MILLISECONDS
            )
        ) return@withContext onReady(false)
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createPreviewSession(onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; onReady(
                        false
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; onReady(
                        false
                    )
                }
            }, backgroundHandler)
        } catch (_: SecurityException) {
            cameraOpenCloseLock.release(); onReady(false)
        }
    }

    private fun createPreviewSession(onReady: (Boolean) -> Unit) {
        val device = cameraDevice ?: return onReady(false)
        val surfaceTexture = textureView?.surfaceTexture ?: return onReady(false)

        previewSize = pickSizeFor(currentAspect, isRecord)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(surfaceTexture)
        previewSurface = surface

        imageReader?.close()
        Log.e("duy.nguyen2", "createPreviewSession: $previewSize")
        imageReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.JPEG, 1
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    saveJpegToAppDir(context, image)
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }

        try {
            previewRequestBuilder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(
                        CaptureRequest.FLASH_MODE,
                        if (flashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                    )
                }

            val surfaces = listOf(surface, imageReader!!.surface)
            device.createCaptureSession(
                surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder!!.build(), null, backgroundHandler
                            )
                            onReady(true)
                        } catch (_: Exception) {
                            onReady(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) = onReady(false)
                }, backgroundHandler
            )
        } catch (_: Exception) {
            onReady(false)
        }
    }

    suspend fun pause() = withContext(Dispatchers.Default) {
        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }
        cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    suspend fun switchCamera() = withContext(Dispatchers.Default) {
        if (!isSwitching.compareAndSet(false, true)) return@withContext
        try {
            try {
                captureSession?.stopRepeating()
            } catch (_: Throwable) {
            }
            try {
                captureSession?.close()
            } catch (_: Throwable) {
            }
            captureSession = null
            try {
                cameraDevice?.close()
            } catch (_: Throwable) {
            }
            cameraDevice = null
            imageReader?.close(); imageReader = null

            currentFacing =
                if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            previewSize = pickSizeFor(currentAspect, isRecord)
            Log.e("duy.nguyen2", "switchCamera: $previewSize")
            startPreview { }
        } finally {
            isSwitching.set(false)
        }
    }

    suspend fun resume(): Unit = withContext(Dispatchers.Default) {
        startPreview { _ -> }
    }

    suspend fun stop() = withContext(Dispatchers.Default) {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        imageReader?.close(); imageReader = null
        stopBackgroundThread()
    }

    suspend fun setFlash(enabled: Boolean) = withContext(Dispatchers.Default) {
        flashOn = enabled
        previewRequestBuilder?.apply {
            set(
                CaptureRequest.FLASH_MODE,
                if (flashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            try {
                captureSession?.setRepeatingRequest(build(), null, backgroundHandler)
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun onAspectChanged(aspect: CameraViewModel.Aspect) = withContext(Dispatchers.Default) {
        currentAspect = aspect
        previewSize = pickSizeFor(aspect, isRecord)
        Log.e("duy.nguyen2", "onAspectChanged: $previewSize")
        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }
        captureSession = null
        if (cameraDevice != null && textureView?.isAvailable == true) {
            createPreviewSession { }
        }
    }

    private fun pickSizeFor(
        aspect: CameraViewModel.Aspect, forVideo: Boolean = false
    ): Size {
        return if (!forVideo) {
            if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
                when (aspect) {
                    CameraViewModel.Aspect.RATIO_16_9 -> Size(4000, 2252)
                    CameraViewModel.Aspect.RATIO_3_4 -> Size(4000, 3000)
                    CameraViewModel.Aspect.RATIO_1_1 -> Size(2292, 2292)
                }
            } else {
                when (aspect) {
                    CameraViewModel.Aspect.RATIO_16_9 -> Size(3392, 1908)
                    CameraViewModel.Aspect.RATIO_3_4 -> Size(3392, 2544)
                    CameraViewModel.Aspect.RATIO_1_1 -> Size(2554, 2554)
                }
            }
        } else {
            when (aspect) {
                CameraViewModel.Aspect.RATIO_16_9 -> Size(1920, 1080)
                CameraViewModel.Aspect.RATIO_3_4 -> Size(1440, 1080)
                CameraViewModel.Aspect.RATIO_1_1 -> Size(1080, 1080)
            }
        }
    }

    suspend fun captureStill() = withContext(Dispatchers.Default) {
        val device = cameraDevice ?: return@withContext
        val session = captureSession ?: return@withContext
        val jpegSurface = imageReader?.surface ?: return@withContext
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees())
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            session.captureBurst(listOf<CaptureRequest>(builder.build()), null, backgroundHandler)
            previewRequestBuilder?.build()
                ?.let { session.setRepeatingRequest(it, null, backgroundHandler) }
        } catch (_: Throwable) {
        }
    }

    suspend fun startRecording() = withContext(Dispatchers.Default) {
        val device = cameraDevice ?: throw IllegalStateException("Camera chưa mở")
        val pSurface =
            previewSurface ?: throw IllegalStateException("Preview surface chưa sẵn sàng")

        val uri = createVideoUri(context, fileName())
            ?: throw IllegalStateException("Không tạo được URI video")
        recordingUri = uri
        val pfd = context.contentResolver.openFileDescriptor(uri, "w")
        val mr = MediaRecorder().also { mediaRecorder = it }
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
            val videoSize = pickSizeFor(currentAspect, forVideo = true)
            setVideoSize(videoSize.width, videoSize.height)
            prepare()
        }
        recorderSurface = mr.surface

        recreateSession(device, listOf(pSurface, mr.surface)) { builder ->
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }
        mr.start()
    }

    suspend fun stopRecording() = withContext(Dispatchers.Default) {
        val mr = mediaRecorder
        if (mr != null) {
            try {
                mr.stop()
            } catch (_: Exception) {
            }
            try {
                mr.reset()
            } catch (_: Exception) {
            }
            try {
                mr.release()
            } catch (_: Exception) {
            }
        }
        mediaRecorder = null
        recorderSurface?.release(); recorderSurface = null

        val device = cameraDevice ?: return@withContext
        val pSurface = previewSurface ?: return@withContext
        recreateSession(device, listOf(pSurface)) { builder ->
            builder.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    private suspend fun recreateSession(
        device: CameraDevice, surfaces: List<Surface>, configure: (CaptureRequest.Builder) -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            device.createCaptureSession(
                surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        surfaces.forEach { builder.addTarget(it) }
                        configure(builder)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        cont.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resume(Unit)
                    }
                }, backgroundHandler
            )
        } catch (_: Exception) {
            cont.resume(Unit)
        }
    }

    @SuppressLint("ServiceCast")
    private fun orientationDegrees(): Int {
        val id = cameraId ?: return 0
        val chars = cameraManager.getCameraCharacteristics(id)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing =
            chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK

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
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
        }
        backgroundThread = null; backgroundHandler = null
    }

    private fun chooseCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == currentFacing) {
                cameraId = id; return
            }
        }
        cameraId = cameraManager.cameraIdList.first()
    }

    private fun fileName(): String = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
}