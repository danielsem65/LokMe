package com.lokme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lokme.service.CommandService

class LokMeApp : Application() {

    companion object {
        const val CHANNEL_ID = "lokme_service"
        private const val PREFS_NAME = "lokme_prefs"
        private const val KEY_SERVICE_SHOULD_RUN = "service_should_run"

        const val SUPABASE_URL = "https://qiykuhhrgvtwsltcemwp.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFpeWt1aGhyZ3Z0d3NsdGNlbXdwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM3MjA5MTgsImV4cCI6MjA5OTI5NjkxOH0.HebOEOrtsF2ACJXBpqPH2koz40f2k1_ug2Fym01TgYc"
        const val SERVER_URL = "wss://lokme-server.onrender.com/ws"
        const val SERVER_API = "https://lokme-server.onrender.com"

        fun setServiceShouldRun(context: Context, shouldRun: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SERVICE_SHOULD_RUN, shouldRun).apply()
        }

        fun serviceShouldRun(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SERVICE_SHOULD_RUN, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        autoStartServiceIfNeeded()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun autoStartServiceIfNeeded() {
        if (serviceShouldRun(this) && !CommandService.isRunning) {
            Log.d("LokMeApp", "Auto-starting service (was previously running)")
            try {
                val intent = Intent(this, CommandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("LokMeApp", "Auto-start failed: ${e.message}")
            }
        }
    }
}
