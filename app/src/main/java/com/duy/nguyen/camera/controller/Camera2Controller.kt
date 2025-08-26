package com.duy.nguyen.camera.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.duy.nguyen.camera.model.CameraViewModel
import com.duy.nguyen.camera.util.saveJpegToAppDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Camera2Controller(
    private val context: Context,
    private val cameraManager: CameraManager
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
    private var previewSize: Size = Size(1080, 1440) // default 3:4

    fun attachPreview(view: TextureView) {
        textureView = view
        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                previewSurface = Surface(surface)
                // mở camera ngay khi surface available
                kotlinx.coroutines.GlobalScope.launch {
                    startPreview { ready -> Log.d("Camera2Controller", "Preview ready=$ready") }
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    suspend fun startPreview(onReady: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        if (cameraDevice != null && captureSession != null && previewSurface != null) {
            // đã có rồi thì chỉ cần set repeating
            resume()
            onReady(true)
            return@withContext
        }
        if (previewSurface == null && textureView?.isAvailable == true) {
            previewSurface = Surface(textureView!!.surfaceTexture)
        }
        if (previewSurface == null) {
            onReady(false)
            return@withContext
        }

        startBackgroundThread()

        chooseCamera()
        val id = cameraId ?: return@withContext onReady(false)

        if (!cameraOpenCloseLock.tryAcquire(
                2500,
                TimeUnit.MILLISECONDS
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
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    onReady(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    onReady(false)
                }
            }, backgroundHandler)
        } catch (se: SecurityException) {
            cameraOpenCloseLock.release()
            onReady(false)
        }
    }

    private fun createPreviewSession(onReady: (Boolean) -> Unit) {
        val device = cameraDevice ?: return onReady(false)
        val surfaceTexture = textureView?.surfaceTexture ?: return onReady(false)

        // 1) set buffer đúng size
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(surfaceTexture)
        previewSurface = surface

        // 2) ImageReader JPEG để nhận ảnh chụp
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.JPEG,
            2
        ).apply {
            setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        saveJpegToAppDir(context, image)
                    } finally {
                        image.close()
                    }
                },
                backgroundHandler
            )
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

            // 3) Session với cả preview + JPEG
            val surfaces = listOf(surface, imageReader!!.surface)
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            val req = previewRequestBuilder!!.build()
                            session.setRepeatingRequest(req, null, backgroundHandler)
                            onReady(true)
                        } catch (e: Exception) {
                            onReady(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) = onReady(false)
                },
                backgroundHandler
            )
        } catch (e: Exception) {
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
        imageReader?.close()
        imageReader = null
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

            currentFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

            startPreview { /* ignore */ }
        } finally {
            isSwitching.set(false)
        }
    }

    suspend fun resume(): Unit = withContext(Dispatchers.Default) {
        startPreview { /* ignore */ }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.Default) {
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
        previewSize = pickSizeFor(aspect)
        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }
        captureSession = null
        // Recreate session với size mới (nếu texture sẵn sàng)
        if (cameraDevice != null && textureView?.isAvailable == true) {
            createPreviewSession { /* ignore */ }
        }
    }


    private fun pickSizeFor(aspect: CameraViewModel.Aspect): Size = when (aspect) {
        CameraViewModel.Aspect.RATIO_16_9 -> Size(4000, 2252)    // 16:9
        CameraViewModel.Aspect.RATIO_3_4 -> Size(4000, 3000)   // 4:3
        CameraViewModel.Aspect.RATIO_1_1 -> Size(2292, 2292)   // 1:1
    }


    suspend fun captureStill() = withContext(Dispatchers.Default) {
        val device = cameraDevice ?: return@withContext
        val session = captureSession ?: return@withContext
        val jpegSurface = imageReader?.surface ?: return@withContext
        try {
            val captureBuilder = device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(jpegSurface)
                set(JPEG_ORIENTATION, jpegOrientationForCapture())
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            session.stopRepeating()
            session.captureBurst(listOf(captureBuilder.build()), null, backgroundHandler)
            // resume preview
            previewRequestBuilder?.build()
                ?.let { session.setRepeatingRequest(it, null, backgroundHandler) }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("ServiceCast")
    private fun jpegOrientationForCapture(): Int {
        val id = cameraId ?: return 0
        val chars = cameraManager.getCameraCharacteristics(id)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING)

        val rotation = (context.display?.rotation)
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

        val deviceDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val base = (sensor + deviceDegrees) % 360
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (360 - base) % 360
        } else {
            base
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
        backgroundThread = null
        backgroundHandler = null
    }

    private fun chooseCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == currentFacing) {
                cameraId = id
                return
            }
        }
        // fallback nếu không tìm được facing mong muốn
        cameraId = cameraManager.cameraIdList.first()
    }

}
