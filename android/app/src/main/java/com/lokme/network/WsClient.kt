package com.lokme.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WsClient(
    private val url: String,
    private val onCommand: (commandType: String, commandId: String, payload: String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var retryCount = 0
    @Volatile private var isManualClose = false
    @Volatile private var currentDeviceId = ""

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("WS", "Connected")
            retryCount = 0
            if (currentDeviceId.isNotEmpty()) {
                sendRegistration(currentDeviceId)
            }
            onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val type = json.getString("command_type")
                val id = json.getString("command_id")
                val payload = json.optString("payload", "")
                onCommand(type, id, payload)
            } catch (e: Exception) {
                Log.e("WS", "Parse error: ${e.message}")
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(code, reason)
            onDisconnected()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onDisconnected()
            if (!isManualClose) scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e("WS", "Error: ${t.message}")
            onDisconnected()
            if (!isManualClose) scheduleReconnect()
        }
    }

    fun connect(deviceId: String) {
        currentDeviceId = deviceId
        isManualClose = false
        try {
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, listener)
            Log.d("WS", "Connecting to $url")
        } catch (e: Exception) {
            Log.e("WS", "Connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    fun sendRegistration(deviceId: String) {
        val json = JSONObject().apply {
            put("type", "register")
            put("device_id", deviceId)
        }
        webSocket?.send(json.toString())
    }

    fun sendResponse(commandId: String, deviceId: String, commandType: String, success: Boolean, data: String = "") {
        val json = JSONObject().apply {
            put("type", "response")
            put("command_id", commandId)
            put("device_id", deviceId)
            put("command_type", commandType)
            put("success", success)
            put("data", data)
        }
        webSocket?.send(json.toString())
    }

    fun sendHeartbeat(deviceId: String) {
        val json = JSONObject().apply {
            put("type", "heartbeat")
            put("device_id", deviceId)
        }
        webSocket?.send(json.toString())
    }

    fun sendVideoFrame(deviceId: String, cameraType: String, jpegBytes: ByteArray) {
        val header = JSONObject().apply {
            put("type", "video_frame")
            put("device_id", deviceId)
            put("camera", cameraType)
        }
        sendBinary(header, jpegBytes)
    }

    fun sendAudioFrame(deviceId: String, pcmBytes: ByteArray) {
        val header = JSONObject().apply {
            put("type", "audio_frame")
            put("device_id", deviceId)
            put("sample_rate", 16000)
            put("channels", 1)
            put("encoding", "pcm_s16le")
        }
        sendBinary(header, pcmBytes)
    }

    private fun sendBinary(header: JSONObject, data: ByteArray) {
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val headerLen = headerBytes.size

        val buffer = ByteArray(2 + headerLen + data.size)
        buffer[0] = (headerLen shr 8).toByte()
        buffer[1] = headerLen.toByte()
        System.arraycopy(headerBytes, 0, buffer, 2, headerLen)
        System.arraycopy(data, 0, buffer, 2 + headerLen, data.size)

        webSocket?.send(buffer.toByteString(0, buffer.size))
    }

    fun close() {
        isManualClose = true
        try { webSocket?.close(1000, "Closing") } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        val delay = (2000L * (retryCount + 1)).coerceAtMost(60_000L)
        retryCount++
        Log.d("WS", "Reconnecting in ${delay}ms (attempt $retryCount)")
        Thread {
            try {
                Thread.sleep(delay)
                if (!isManualClose && currentDeviceId.isNotEmpty()) {
                    connect(currentDeviceId)
                }
            } catch (_: InterruptedException) {}
        }.start()
    }
}
