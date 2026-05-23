package com.otgcam.agent

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.otgcam.agent.ui.LogAdapter
import com.otgcam.agent.ui.SetupFragment

/**
 * Entry point for the OTGCam Agent application.
 *
 * On first launch: shows [SetupFragment] for credential entry.
 * On subsequent launches: shows the main status / control screen.
 *
 * Registers [HeadsetReceiver] via AudioManager for earpiece button capture,
 * and subscribes to local broadcasts from [CameraService] for live status updates.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLastCapture: TextView
    private lateinit var tvLastUpload: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var rvLog: RecyclerView
    private lateinit var logAdapter: LogAdapter
    private lateinit var audioManager: AudioManager

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(CameraService.EXTRA_STATUS_MESSAGE) ?: return
            tvStatus.text = msg
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(CameraService.EXTRA_STATUS_MESSAGE) ?: return
            logAdapter.addEntry(msg)
            rvLog.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences("otgcam_prefs", MODE_PRIVATE)
        val setupDone = prefs.getBoolean(SetupFragment.KEY_SETUP_DONE, false)

        if (!setupDone) {
            setContentView(R.layout.activity_main)
            showSetupFragment()
            return
        }

        setContentView(R.layout.activity_main)
        initMainScreen()
    }

    override fun onResume() {
        super.onResume()

        @Suppress("DEPRECATION")
        audioManager.registerMediaButtonEventReceiver(
            android.content.ComponentName(this, HeadsetReceiver::class.java)
        )

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(statusReceiver, IntentFilter(CameraService.ACTION_STATUS_UPDATE))
            registerReceiver(logReceiver,    IntentFilter(CameraService.ACTION_LOG_UPDATE))
        }

        requestAllPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }

        // Restore button state
        val prefs = getSharedPreferences("otgcam_prefs", MODE_PRIVATE)
        val running = prefs.getBoolean("service_running", false)
        if (::btnStart.isInitialized) {
            btnStart.isEnabled = !running
            btnStop.isEnabled  = running
        }
    }

    override fun onPause() {
        super.onPause()

        @Suppress("DEPRECATION")
        audioManager.unregisterMediaButtonEventReceiver(
            android.content.ComponentName(this, HeadsetReceiver::class.java)
        )

        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(statusReceiver)
            unregisterReceiver(logReceiver)
        }
    }

    // -------------------------------------------------------------------------
    // Screen setup
    // -------------------------------------------------------------------------

    private fun showSetupFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SetupFragment())
            .commit()
    }

    /** Called by [SetupFragment] after successful credential save. */
    fun showMainScreen() {
        supportFragmentManager.beginTransaction()
            .remove(supportFragmentManager.findFragmentById(R.id.fragmentContainer) ?: return)
            .commit()
        initMainScreen()
    }

    private fun initMainScreen() {
        tvStatus       = findViewById(R.id.tvStatus)
        tvLastCapture  = findViewById(R.id.tvLastCapture)
        tvLastUpload   = findViewById(R.id.tvLastUpload)
        btnStart       = findViewById(R.id.btnStartService)
        btnStop        = findViewById(R.id.btnStopService)
        rvLog          = findViewById(R.id.rvLog)

        logAdapter = LogAdapter()
        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter

        btnStart.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_START }
            )
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
            getSharedPreferences("otgcam_prefs", MODE_PRIVATE)
                .edit().putBoolean("service_running", true).apply()
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STOP }
            )
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
            getSharedPreferences("otgcam_prefs", MODE_PRIVATE)
                .edit().putBoolean("service_running", false).apply()
        }

        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            logAdapter.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestAllPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ).also {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                it.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST_CODE) return

        val denied = permissions.filterIndexed { i, _ ->
            grantResults[i] != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            val message = buildString {
                append(getString(R.string.perm_required_message))
                append("\n\n")
                denied.forEach { perm ->
                    append("• ")
                    append(permissionLabel(perm))
                    append("\n")
                }
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_required_title))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.retry)) { _, _ -> requestAllPermissions() }
                .show()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        Manifest.permission.CAMERA              -> getString(R.string.perm_label_camera)
        Manifest.permission.RECORD_AUDIO        -> getString(R.string.perm_label_audio)
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> getString(R.string.perm_label_storage)
        Manifest.permission.READ_EXTERNAL_STORAGE  -> getString(R.string.perm_label_storage)
        else -> permission
    }

    companion object {
        private const val PERM_REQUEST_CODE = 100
    }
}
