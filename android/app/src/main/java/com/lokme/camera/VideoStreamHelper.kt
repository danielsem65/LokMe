package com.lokme.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lokme.network.WsClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoStreamHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val isStreaming = AtomicBoolean(false)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var currentCameraType = "back"

    fun initialize() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
        future.get()
    }

    fun startStream(wsClient: WsClient, deviceId: String, useFrontCamera: Boolean) {
        if (isStreaming.get()) return

        val provider = cameraProvider ?: run {
            Log.e("VideoStream", "Camera provider not initialized")
            return
        }

        currentCameraType = if (useFrontCamera) "front" else "back"

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            if (!isStreaming.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val jpegBytes = imageProxyToJpeg(imageProxy)
            imageProxy.close()

            if (jpegBytes != null && isStreaming.get()) {
                wsClient.sendVideoFrame(deviceId, currentCameraType, jpegBytes)
            }
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
            isStreaming.set(true)
            Log.d("VideoStream", "Started streaming from $currentCameraType camera")
        } catch (e: Exception) {
            Log.e("VideoStream", "Failed to start stream", e)
        }
    }

    fun stopStream() {
        isStreaming.set(false)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        Log.d("VideoStream", "Stream stopped")
    }

    fun isStreaming(): Boolean = isStreaming.get()

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val nv21 = imageProxyToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e("VideoStream", "Frame conversion error: ${e.message}")
            null
        }
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}
