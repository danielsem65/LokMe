package com.lokme

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.lokme.admin.DeviceAdminReceiver
import com.lokme.databinding.ActivityMainBinding
import com.lokme.network.SupabaseClient
import com.lokme.service.CommandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val enableAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Device Admin enabled", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        requestPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)

        binding.btnEnableAdmin.setOnClickListener { promptEnableAdmin() }
        binding.btnStartService.setOnClickListener { startMonitoringService() }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun promptEnableAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device admin is required for remote lock functionality."
            )
        }
        enableAdminLauncher.launch(intent)
    }

    private fun startMonitoringService() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Toast.makeText(this, "Enable Device Admin first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        } else {
            startService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startService()
        }
    }

    private fun startService() {
        val serviceIntent = Intent(this, CommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Register device in Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SupabaseClient.registerDevice(
                    this@MainActivity,
                    deviceName = Build.MODEL,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = "Android ${Build.VERSION.RELEASE}"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val adminActive = devicePolicyManager.isAdminActive(adminComponent)
        binding.tvAdminStatus.text = if (adminActive) "Device Admin: Active" else "Device Admin: Inactive"
        binding.tvAdminStatus.setTextColor(getColor(if (adminActive) android.R.color.holo_green_light else android.R.color.holo_red_light))

        val serviceRunning = CommandService.isRunning
        binding.tvServiceStatus.text = if (serviceRunning) "Service: Running" else "Service: Stopped"
        binding.tvServiceStatus.setTextColor(getColor(if (serviceRunning) android.R.color.holo_green_light else android.R.color.holo_red_light))

        binding.btnEnableAdmin.isEnabled = !adminActive
        binding.btnStartService.isEnabled = adminActive && !serviceRunning
    }
}
