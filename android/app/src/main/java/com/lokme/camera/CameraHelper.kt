package com.lokme.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize() {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    cameraProvider = future.get()
                    latch.countDown()
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e("CameraHelper", "Init error: ${e.message}")
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
    }

    fun capturePhoto(
        useFrontCamera: Boolean,
        onResult: (ByteArray) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val provider = cameraProvider
        if (provider == null) {
            onError(IllegalStateException("Camera not initialized"))
            return
        }

        mainHandler.post {
            try {
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                        else CameraSelector.LENS_FACING_BACK
                    )
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()
                                provider.unbindAll()
                                onResult(bytes)
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }

                        override fun onError(e: ImageCaptureException) {
                            provider.unbindAll()
                            onError(e)
                        }
                    }
                )
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun generateFileName(): String {
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
    }
}
