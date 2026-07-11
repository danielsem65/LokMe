package com.lokme.camera

import android.content.Context
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
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // ImageProxy from ImageAnalysis gives YUV_420_888
            // We need to convert to JPEG
            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width, image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )

            // Use YUV to RGB conversion
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val yuvBytes = ByteArray(ySize + uSize + vSize)
            yBuffer.get(yuvBytes, 0, ySize)
            uBuffer.get(yuvBytes, ySize, uSize)
            vBuffer.get(yuvBytes, ySize + uSize, vSize)

            val argb = yuv420ToArgb(yuvBytes, image.width, image.height)
            bitmap.setPixels(argb, 0, image.width, 0, 0, image.width, image.height)

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            bitmap.recycle()

            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("VideoStream", "Frame conversion error", e.message)
            null
        }
    }

    private fun yuv420ToArgb(yuv: ByteArray, width: Int, height: Int): IntArray {
        val argb = IntArray(width * height)
        val frameSize = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val yp = j * width + i
                val up = frameSize + (j / 2) * (width / 2) + (i / 2)
                val vp = up + frameSize / 4

                if (yp < yuv.size && up < yuv.size && vp < yuv.size) {
                    val y = (yuv[yp].toInt() and 0xFF) - 16
                    val u = (yuv[up].toInt() and 0xFF) - 128
                    val v = (yuv[vp].toInt() and 0xFF) - 128

                    val r = (1.164 * y + 1.596 * v).toInt().coerceIn(0, 255)
                    val g = (1.164 * y - 0.813 * v - 0.391 * u).toInt().coerceIn(0, 255)
                    val b = (1.164 * y + 2.018 * u).toInt().coerceIn(0, 255)

                    argb[yp] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return argb
    }
}
