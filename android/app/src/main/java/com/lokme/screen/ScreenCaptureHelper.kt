package com.lokme.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

object ScreenCaptureHelper {

    private const val TAG = "ScreenCapture"
    private const val VIRTUAL_DISPLAY_NAME = "lokme_screen_capture"
    private const val PREFS_NAME = "lokme_screen"
    private const val KEY_TOKEN_DATA = "projection_token"
    private const val KEY_RESULT_CODE = "projection_result"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var dataDir: String = ""

    private const val REQUEST_CODE = 1001

    fun getRequestCode(): Int = REQUEST_CODE

    fun getLaunchIntent(context: Context): Intent? {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun storeProjectionToken(context: Context, resultCode: Int, data: Intent) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RESULT_CODE, resultCode)
            .putString(KEY_TOKEN_DATA, data.toUri(0))
            .apply()
    }

    fun hasProjectionToken(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN_DATA, null) != null
    }

    fun setupProjection(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val resultCode = prefs.getInt(KEY_RESULT_CODE, Activity.RESULT_CANCELED)
        val tokenUri = prefs.getString(KEY_TOKEN_DATA, null) ?: return

        if (resultCode != Activity.RESULT_OK) return

        try {
            val intent = Intent.parseUri(tokenUri, 0)
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, intent)

            ensureVirtualDisplay(context)
            Log.d(TAG, "MediaProjection setup from stored token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore projection: ${e.message}")
        }
    }

    fun setDataDir(dir: String) {
        dataDir = dir
    }

    private fun ensureVirtualDisplay(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.d(TAG, "VirtualDisplay created: ${width}x${height}")
    }

    fun captureScreen(context: Context? = null): ByteArray? {
        try {
            // ensure virtual display is alive
            if (mediaProjection == null && context != null) {
                setupProjection(context)
            }
            if (mediaProjection != null && context != null) {
                ensureVirtualDisplay(context)
            }

            val reader = imageReader ?: return null

            val image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }

            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val bytes = stream.toByteArray()
            stream.close()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()

            Log.d(TAG, "Screen captured: ${bytes.size} bytes")
            return bytes
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}")
            return null
        }
    }

    fun cleanup() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
