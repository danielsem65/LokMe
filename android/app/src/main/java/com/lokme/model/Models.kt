package com.lokme.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val id: String,
    val device_name: String,
    val device_model: String,
    val android_version: String,
    val is_online: Boolean = true
)

@Serializable
data class Command(
    val id: String,
    val device_id: String,
    val command_type: String,
    val payload: String = "",
    val status: String = "pending"
)

@Serializable
data class CommandResponse(
    val command_id: String,
    val device_id: String,
    val command_type: String,
    val success: Boolean,
    val data: String = ""
)

@Serializable
data class LocationData(
    val device_id: String,
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class CallLogEntry(
    val device_id: String,
    val phone_number: String,
    val contact_name: String,
    val call_type: String,
    val call_date: String,
    val duration_seconds: Long
)

@Serializable
data class PhotoUpload(
    val device_id: String,
    val storage_url: String,
    val camera_type: String
)
