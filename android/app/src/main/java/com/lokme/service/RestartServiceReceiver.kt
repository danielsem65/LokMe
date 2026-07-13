package com.lokme.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class RestartServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RestartReceiver", "Received: ${intent.action}")

        if (!CommandService.isRunning) {
            Thread {
                try {
                    Thread.sleep(2000)
                    val serviceIntent = Intent(context, CommandService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("RestartReceiver", "Service restarted")
                } catch (e: Exception) {
                    Log.e("RestartReceiver", "Restart failed: ${e.message}")
                }
            }.start()
        }
    }
}
