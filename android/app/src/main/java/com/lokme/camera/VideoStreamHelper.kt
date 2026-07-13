package com.lokme.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCameraType = "back"

    fun initialize() {
        mainHandler.post {
            try {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    cameraProvider = future.get()
                    Log.d("VideoStream", "Camera provider initialized")
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e("VideoStream", "Init error: ${e.message}")
            }
        }
    }

    fun startStream(wsClient: WsClient, deviceId: String, useFrontCamera: Boolean) {
        if (isStreaming.get()) return

        val provider = cameraProvider
        if (provider == null) {
            Log.e("VideoStream", "Camera provider not initialized")
            return
        }

        currentCameraType = if (useFrontCamera) "front" else "back"

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            if (!isStreaming.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            try {
                val jpegBytes = imageProxyToJpeg(imageProxy)
                imageProxy.close()

                if (jpegBytes != null && isStreaming.get()) {
                    wsClient.sendVideoFrame(deviceId, currentCameraType, jpegBytes)
                }
            } catch (e: Exception) {
                Log.e("VideoStream", "Frame error: ${e.message}")
                try { imageProxy.close() } catch (_: Exception) {}
            }
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()

        mainHandler.post {
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
                isStreaming.set(true)
                Log.d("VideoStream", "Started streaming from $currentCameraType camera")
            } catch (e: Exception) {
                Log.e("VideoStream", "Failed to start stream", e)
            }
        }
    }

    fun stopStream() {
        isStreaming.set(false)
        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {}
        }
        Log.d("VideoStream", "Stream stopped")
    }

    fun isStreaming(): Boolean = isStreaming.get()

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val nv21 = yuv420888ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 30, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e("VideoStream", "JPEG conversion error: ${e.message}")
            null
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        var pos = 0

        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(yRowStart + col)
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(uvIndex)
                nv21[pos++] = uBuffer.get(uvIndex)
            }
        }

        return nv21
    }
}
