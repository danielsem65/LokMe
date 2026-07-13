package com.lokme.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.lokme.LokMeApp

class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccService"
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        ensureServiceRunning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CommandService.isRunning) {
            ensureServiceRunning()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isRunning = true
        Log.d(TAG, "Accessibility service rebound")
        ensureServiceRunning()
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun ensureServiceRunning() {
        if (!CommandService.isRunning && LokMeApp.serviceShouldRun(this)) {
            Log.d(TAG, "Restarting CommandService from accessibility service")
            try {
                val intent = Intent(this, CommandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service: ${e.message}")
            }
        }
    }
}
