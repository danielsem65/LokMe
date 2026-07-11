package com.lokme.camera

import android.content.Context
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

class CameraHelper(private val context: Context) {

    private var imageCaptureBack: ImageCapture? = null
    private var imageCaptureFront: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val latch = CountDownLatch(1)

    fun initialize(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            imageCaptureBack = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageCaptureFront = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val backSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, backSelector, imageCaptureBack)
                cameraProvider?.bindToLifecycle(lifecycleOwner, frontSelector, imageCaptureFront)
            } catch (e: Exception) {
                Log.e("CameraHelper", "Bind failed", e)
            }
            latch.countDown()
        }, ContextCompat.getMainExecutor(context))

        latch.await(5, TimeUnit.SECONDS)
    }

    fun capturePhoto(
        useFrontCamera: Boolean,
        onResult: (ByteArray) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = if (useFrontCamera) imageCaptureFront else imageCaptureBack
        if (capture == null) {
            onError(IllegalStateException("Camera not initialized"))
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    onResult(bytes)
                }

                override fun onError(e: ImageCaptureException) {
                    onError(e)
                }
            }
        )
    }

    fun generateFileName(): String {
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
    }
}
