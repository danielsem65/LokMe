package com.lokme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LokMeApp : Application() {

    companion object {
        const val CHANNEL_ID = "lokme_service"

        // ========== FILL IN YOUR DETAILS ==========
        const val SUPABASE_URL = "https://qiykuhhrgvtwsltcemwp.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFpeWt1aGhyZ3Z0d3NsdGNlbXdwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM3MjA5MTgsImV4cCI6MjA5OTI5NjkxOH0.HebOEOrtsF2ACJXBpqPH2koz40f2k1_ug2Fym01TgYc"
        const val SERVER_URL = "wss://lokme-sever.onrender.com/ws"
        const val SERVER_API = "https://lokme-sever.onrender.com"
        // ===========================================
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
}
