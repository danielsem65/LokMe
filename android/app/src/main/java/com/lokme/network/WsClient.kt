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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var retryCount = 0
    @Volatile private var isManualClose = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("WS", "Connected")
            retryCount = 0
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
        isManualClose = false
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)

        Thread {
            Thread.sleep(1000)
            sendRegistration(deviceId)
        }.start()
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
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val headerLen = headerBytes.size

        // Format: [2 bytes header length][header json][jpeg bytes]
        val buffer = ByteArray(2 + headerLen + jpegBytes.size)
        buffer[0] = (headerLen shr 8).toByte()
        buffer[1] = headerLen.toByte()
        System.arraycopy(headerBytes, 0, buffer, 2, headerLen)
        System.arraycopy(jpegBytes, 0, buffer, 2 + headerLen, jpegBytes.size)

        webSocket?.send(buffer.toByteString(0, buffer.size))
    }

    fun close() {
        isManualClose = true
        webSocket?.close(1000, "Closing")
    }

    private fun scheduleReconnect() {
        val delay = (1000L * (retryCount + 1)).coerceAtMost(30_000L)
        retryCount++
        Log.d("WS", "Reconnecting in ${delay}ms")
        Thread { Thread.sleep(delay); if (!isManualClose) connect("") }.start()
    }
}
