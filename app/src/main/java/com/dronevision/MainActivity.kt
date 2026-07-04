package com.dronevision

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dronevision.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    // Step 3: user grants screen capture -> start the service.
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(svc) else startService(svc)
                Toast.makeText(this, "Detection started", Toast.LENGTH_SHORT).show()
                refreshButtons()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP))
            refreshButtons()
        }
        binding.btnSave.setOnClickListener { saveCurrentFrame() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
    }

    private fun onStartClicked() {
        // Step 1: overlay permission (draw boxes over the drone app).
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please allow 'Draw over other apps'", Toast.LENGTH_LONG).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        // Step 2: ask user to disable battery optimisation (keeps service alive).
        requestIgnoreBatteryOptimisation()
        // Step 3: screen capture consent dialog.
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun saveCurrentFrame() {
        val frame = CaptureService.snapshotFrame()
        if (frame == null || !CaptureService.running) {
            Toast.makeText(this, "Start detection first", Toast.LENGTH_SHORT).show()
            return
        }
        val file = DetectionStore.save(this, frame, CaptureService.lastDetections)
        frame.recycle()
        Toast.makeText(
            this,
            if (file != null) "Saved to History" else "Save failed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestIgnoreBatteryOptimisation() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        } catch (_: Exception) { /* some OEMs block this intent; not fatal */ }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshButtons() {
        val running = CaptureService.running
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnSave.isEnabled = running
        binding.txtStatus.text = if (running) "Status: running" else "Status: stopped"
    }
}
