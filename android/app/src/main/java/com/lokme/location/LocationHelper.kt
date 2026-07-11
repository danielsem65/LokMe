package com.lokme.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

object LocationHelper {

    data class LatLng(val latitude: Double, val longitude: Double)

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(
        context: Context,
        onResult: (LatLng) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()

        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cts.token
        ).addOnSuccessListener { location ->
            if (location != null) {
                onResult(LatLng(location.latitude, location.longitude))
            } else {
                fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        onResult(LatLng(lastLoc.latitude, lastLoc.longitude))
                    } else {
                        onError(IllegalStateException("No location available"))
                    }
                }.addOnFailureListener(onError)
            }
        }.addOnFailureListener(onError)
    }
}
