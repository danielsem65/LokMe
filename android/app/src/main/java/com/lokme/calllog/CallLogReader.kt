package com.lokme.calllog

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Locale

data class CallLogEntry(
    val phoneNumber: String,
    val contactName: String,
    val callType: String,
    val callDate: String,
    val durationSeconds: Long
) {
    fun toMap(deviceId: String): Map<String, Any> = mapOf(
        "device_id" to deviceId,
        "phone_number" to phoneNumber,
        "contact_name" to contactName,
        "call_type" to callType,
        "call_date" to callDate,
        "duration_seconds" to durationSeconds
    )
}

object CallLogReader {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun readCallLogs(context: Context, limit: Int = 50): List<CallLogEntry> {
        val entries = mutableListOf<CallLogEntry>()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()

        val cursor: Cursor? = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use {
            val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

            while (it.moveToNext()) {
                entries.add(
                    CallLogEntry(
                        phoneNumber = it.getString(numberIdx) ?: "Unknown",
                        contactName = it.getString(nameIdx) ?: "Unknown",
                        callType = typeToString(it.getInt(typeIdx)),
                        callDate = dateFormat.format(it.getLong(dateIdx)),
                        durationSeconds = it.getLong(durationIdx)
                    )
                )
            }
        }

        return entries
    }

    private fun typeToString(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "unknown"
    }
}
