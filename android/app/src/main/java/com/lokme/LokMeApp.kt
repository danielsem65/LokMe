package com.lokme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LokMeApp : Application() {

    companion object {
        const val CHANNEL_ID = "lokme_service"

        // ========== FILL IN YOUR DETAILS ==========
        const val SUPABASE_URL = "https://YOUR_PROJECT.supabase.co"
        const val SUPABASE_KEY = "YOUR_ANON_KEY"
        const val SERVER_URL = "wss://your-server.onrender.com/ws"
        const val SERVER_API = "https://your-server.onrender.com"
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
