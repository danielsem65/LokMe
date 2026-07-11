package com.lokme.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.lokme.LokMeApp
import com.lokme.MainActivity
import com.lokme.R
import com.lokme.network.SupabaseClient
import com.lokme.network.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommandService : LifecycleService() {

    private var wsClient: WsClient? = null
    private var executor: CommandExecutor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var deviceId: String = ""

    companion object {
        private const val TAG = "CommandService"
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        deviceId = SupabaseClient.getDeviceId(this)
        executor = CommandExecutor(this)
        executor?.initCamera(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(1, buildNotification())
        connectWebSocket()
        return START_STICKY
    }

    private fun connectWebSocket() {
        wsClient = WsClient(
            url = LokMeApp.SERVER_URL,
            onCommand = { commandType, commandId, payload ->
                handleCommand(commandType, commandId, payload)
            },
            onConnected = {
                Log.d(TAG, "WebSocket connected")
                scope.launch {
                    try {
                        SupabaseClient.updateDeviceOnline(this@CommandService, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Online update failed", e)
                    }
                }
            },
            onDisconnected = {
                Log.d(TAG, "WebSocket disconnected")
            }
        )
        wsClient?.connect(deviceId)

        // Heartbeat every 60s
        Thread {
            while (isRunning) {
                Thread.sleep(60_000)
                wsClient?.sendHeartbeat(deviceId)
            }
        }.start()
    }

    private fun handleCommand(commandType: String, commandId: String, payload: String) {
        Log.d(TAG, "Received command: $commandType ($commandId)")

        executor?.execute(
            commandType = commandType,
            commandId = commandId,
            payload = payload,
            deviceId = deviceId,
            onSuccess = { data ->
                wsClient?.sendResponse(commandId, deviceId, commandType, true, data)
                scope.launch {
                    try {
                        SupabaseClient.updateCommandStatus(commandId, "completed")
                    } catch (_: Exception) {}
                }
            },
            onError = { error ->
                wsClient?.sendResponse(commandId, deviceId, commandType, false, error)
                scope.launch {
                    try {
                        SupabaseClient.updateCommandStatus(commandId, "failed")
                    } catch (_: Exception) {}
                }
            }
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, LokMeApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lokme:service")
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    override fun onDestroy() {
        isRunning = false
        wsClient?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.launch {
            try {
                SupabaseClient.updateDeviceOnline(this@CommandService, false)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
