package com.lokme.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Thread {
                try {
                    Thread.sleep(5000)
                    val serviceIntent = Intent(context, CommandService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("BootReceiver", "Service started after boot")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service: ${e.message}")
                }
            }.start()
        }
    }
}
