package com.otgcam.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.otgcam.agent.model.AppConfig
import com.otgcam.agent.model.CallSignal
import com.otgcam.agent.model.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Core foreground service that keeps the UVC camera operational with the screen off.
 *
 * Handles camera lifecycle, photo/video capture, Telegram uploads with retry queuing,
 * WebRTC call management, and Bluetooth headset audio routing.
 */
class CameraService : Service() {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private var cameraClient: CameraClient? = null
    private var textureView: AspectRatioTextureView? = null
    private var windowManager: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telegramUploader: TelegramUploader? = null
    private var webRtcManager: WebRtcManager? = null
    private var callAutoAcceptor: CallAutoAcceptor? = null
    private var usbMonitor: UsbMonitor? = null
    private var audioManager: AudioManager? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uploadRetryQueue = LinkedList<File>()
    private val inMemoryLog = ArrayDeque<String>(50)

    private val mainHandler = Handler(Looper.getMainLooper())

    // Bluetooth headset
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var bluetoothStateReceiver: BroadcastReceiver? = null

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
    private val videoDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START               -> handleStart()
            ACTION_STOP                -> handleStop()
            ACTION_CAPTURE_PHOTO       -> capturePhoto()
            ACTION_CAPTURE_VIDEO       -> captureVideo(durationSeconds = 30)
            ACTION_TOGGLE_LIVE_AUDIO   -> webRtcManager?.toggleLiveCall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    /**
     * Full service initialisation sequence. The foreground notification is posted first
     * so the service is not killed by the OS before the camera is opened.
     */
    private fun handleStart() {
        // 1. Foreground notification — must be first
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        // 2. Wake lock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OTGCam::ServiceWakeLock")
        wakeLock?.acquire()

        // 3. Load config
        val config = loadConfig() ?: run {
            broadcastLog("Config not found — please complete setup.")
            stopSelf()
            return
        }

        // 4. Uploader
        telegramUploader = TelegramUploader(config)

        // 5. WebRTC
        webRtcManager = WebRtcManager(this, config)
        webRtcManager?.initialize(telegramUploader!!)

        // 6. Call auto-acceptor
        callAutoAcceptor = CallAutoAcceptor(telegramUploader!!, webRtcManager!!, this)
        callAutoAcceptor?.startListening()

        // 7. USB monitor
        usbMonitor = UsbMonitor(this)
        usbMonitor?.start()

        // 8. Hidden overlay surface for UVC preview
        attachHiddenSurface()

        // 9. Open UVC camera
        openUVCCamera()

        // 10. Persist service state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()

        // 11. Status broadcast
        broadcastStatus("Service started. Waiting for UVC camera.")

        // 12. Drain retry queue in background
        serviceScope.launch { drainRetryQueue() }

        // 13. Bluetooth headset setup
        setupBluetoothHeadset()
    }

    /**
     * Tears down all resources in a safe, ordered sequence.
     */
    private fun handleStop() {
        callAutoAcceptor?.stopListening()
        callAutoAcceptor = null

        webRtcManager?.endCall()
        webRtcManager = null

        cameraClient?.closeCamera()
        cameraClient = null

        textureView?.let { windowManager?.removeView(it) }
        textureView = null

        usbMonitor?.stop()
        usbMonitor = null

        teardownBluetoothHeadset()

        serviceScope.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()

        stopForeground(true)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    /**
     * Adds a 1×1 pixel [AspectRatioTextureView] as a system overlay window.
     * Required by the libausbc library as a render target.
     */
    private fun attachHiddenSurface() {
        try {
            val tv = AspectRatioTextureView(this)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

            val params = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager?.addView(tv, params)
            textureView = tv
        } catch (e: SecurityException) {
            broadcastLog("Overlay permission denied — camera may not preview: ${e.message}")
            textureView = null
        } catch (e: Exception) {
            broadcastLog("Failed to attach hidden surface: ${e.message}")
            textureView = null
        }
    }

    /**
     * Builds and opens a [CameraClient] targeting 4K UVC resolution.
     * The library automatically negotiates down to the highest supported resolution.
     */
    private fun openUVCCamera() {
        val tv = textureView ?: run {
            broadcastLog("No surface available — cannot open UVC camera.")
            return
        }

        cameraClient = CameraClient.newBuilder(this)
            .setEnableGLES(true)
            .setRawImage(false)
            .setCameraStrategy(CameraUVC(this))
            .setCameraRequest(
                CameraRequest.Builder()
                    .setPreviewWidth(3840)
                    .setPreviewHeight(2160)
                    .setMinimumFps(30)
                    .setMaximumFps(60)
                    .setRawPreviewData(false)
                    .create()
            )
            .setDefaultCamera(false)
            .build()

        cameraClient?.openCamera(tv, object : ICameraStateCallBack {
            override fun onCameraState(
                self: CameraClient,
                code: ICameraStateCallBack.State,
                msg: String?
            ) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        broadcastStatus("UVC camera connected.")
                        updateNotification("UVC camera connected.")
                        VibrationHelper.vibrateUvcEvent(this@CameraService)
                    }
                    ICameraStateCallBack.State.CLOSED -> {
                        broadcastStatus("UVC camera disconnected.")
                        updateNotification("UVC camera disconnected.")
                        VibrationHelper.vibrateUvcEvent(this@CameraService)
                    }
                    ICameraStateCallBack.State.ERROR -> {
                        broadcastStatus("Camera error: $msg")
                        updateNotification("Camera error.")
                        cameraClient?.closeCamera()
                        mainHandler.postDelayed({ openUVCCamera() }, 3000L)
                    }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Capture
    // -------------------------------------------------------------------------

    /** Captures a single JPEG photo and uploads it to Telegram. */
    private fun capturePhoto() {
        val client = cameraClient ?: run {
            broadcastLog("Cannot capture: camera not connected.")
            return
        }
        val dir = getExternalFilesDir("OTGCam/Photos")
        dir?.mkdirs()
        val filename = "photo_${dateFormat.format(Date())}.jpg"
        val file = File(dir, filename)

        client.captureImage(object : ICaptureCallBack {
            override fun onBegin() {
                broadcastLog("Capturing photo...")
            }
            override fun onComplete(path: String?) {
                VibrationHelper.vibrateCaptureConfirm(this@CameraService)
                broadcastLog("Photo captured: $filename")
                val savedFile = if (path != null) File(path) else file
                serviceScope.launch { uploadPhoto(savedFile) }
            }
            override fun onError(error: String?) {
                broadcastLog("Capture failed: $error")
            }
        }, file.absolutePath)
    }

    /**
     * Records a video for [durationSeconds] seconds, then uploads it to Telegram.
     * @param durationSeconds Recording duration before automatic stop.
     */
    private fun captureVideo(durationSeconds: Int) {
        val client = cameraClient ?: run {
            broadcastLog("Cannot record: camera not connected.")
            return
        }
        val dir = getExternalFilesDir("OTGCam/Videos")
        dir?.mkdirs()
        val filename = "video_${videoDateFormat.format(Date())}.mp4"
        val file = File(dir, filename)

        client.captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {
                VibrationHelper.vibrateCaptureConfirm(this@CameraService)
                broadcastLog("Recording video for ${durationSeconds}s...")
                mainHandler.postDelayed({
                    client.captureVideoStop()
                }, durationSeconds * 1000L)
            }
            override fun onComplete(path: String?) {
                broadcastLog("Video recorded: $filename")
                val savedFile = if (path != null) File(path) else file
                serviceScope.launch { uploadVideo(savedFile) }
            }
            override fun onError(error: String?) {
                broadcastLog("Video recording failed: $error")
            }
        }, file.absolutePath)
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /**
     * Uploads a photo to Telegram. On failure the file is added to the retry queue.
     */
    private suspend fun uploadPhoto(file: File) {
        try {
            val uploader = telegramUploader ?: return
            when (val result = uploader.uploadPhoto(file)) {
                is UploadResult.Success -> {
                    VibrationHelper.vibrateUploadSuccess(this)
                    broadcastLog("Uploaded: ${file.name}")
                    drainRetryQueue()
                }
                is UploadResult.Failure -> {
                    broadcastLog("Upload failed, queued for retry: ${result.reason}")
                    uploadRetryQueue.add(file)
                }
            }
        } catch (e: Exception) {
            broadcastLog("Upload exception: ${e.message}")
            uploadRetryQueue.add(file)
        }
    }

    /**
     * Uploads a video to Telegram. On failure the file is added to the retry queue.
     */
    private suspend fun uploadVideo(file: File) {
        try {
            val uploader = telegramUploader ?: return
            when (val result = uploader.uploadVideo(file)) {
                is UploadResult.Success -> {
                    VibrationHelper.vibrateUploadSuccess(this)
                    broadcastLog("Uploaded: ${file.name}")
                    drainRetryQueue()
                }
                is UploadResult.Failure -> {
                    broadcastLog("Upload failed, queued for retry: ${result.reason}")
                    uploadRetryQueue.add(file)
                }
            }
        } catch (e: Exception) {
            broadcastLog("Upload exception: ${e.message}")
            uploadRetryQueue.add(file)
        }
    }

    /**
     * Attempts to upload all files in [uploadRetryQueue] in FIFO order.
     * Stops on the first failure to avoid spinning on a broken connection.
     */
    private suspend fun drainRetryQueue() {
        val uploader = telegramUploader ?: return
        while (uploadRetryQueue.isNotEmpty()) {
            val file = uploadRetryQueue.peek() ?: break
            val isPhoto = file.name.endsWith(".jpg", ignoreCase = true)
            val result = try {
                if (isPhoto) uploader.uploadPhoto(file) else uploader.uploadVideo(file)
            } catch (e: Exception) {
                UploadResult.Failure("Exception: ${e.message}", file)
            }
            when (result) {
                is UploadResult.Success -> {
                    uploadRetryQueue.poll()
                    broadcastLog("Retry upload succeeded: ${file.name}")
                }
                is UploadResult.Failure -> {
                    // Stop draining; will retry on next successful upload
                    break
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bluetooth headset
    // -------------------------------------------------------------------------

    private fun setupBluetoothHeadset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return  // BT requires BLUETOOTH_CONNECT on S+; skip for now

        try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = proxy as? BluetoothHeadset

                        val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                        bluetoothStateReceiver = object : BroadcastReceiver() {
                            override fun onReceive(ctx: Context, intent: Intent) {
                                val state = intent.getIntExtra(
                                    BluetoothProfile.EXTRA_STATE,
                                    BluetoothProfile.STATE_DISCONNECTED
                                )
                                when (state) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        @Suppress("DEPRECATION")
                                        audioManager?.startBluetoothSco()
                                        audioManager?.isBluetoothScoOn = true
                                        broadcastLog("Bluetooth headset connected — SCO started.")
                                    }
                                    BluetoothProfile.STATE_DISCONNECTED -> {
                                        @Suppress("DEPRECATION")
                                        audioManager?.stopBluetoothSco()
                                        audioManager?.isBluetoothScoOn = false
                                        broadcastLog("Bluetooth headset disconnected.")
                                    }
                                }
                            }
                        }
                        registerReceiver(bluetoothStateReceiver, filter)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    bluetoothHeadset = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            broadcastLog("Bluetooth setup failed: ${e.message}")
        }
    }

    private fun teardownBluetoothHeadset() {
        bluetoothStateReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { /* already unregistered */ }
        }
        bluetoothStateReceiver = null

        try {
            @Suppress("DEPRECATION")
            audioManager?.stopBluetoothSco()
        } catch (e: Exception) { /* ignore */ }

        audioManager?.mode = AudioManager.MODE_NORMAL
        bluetoothHeadset = null
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "OTGCam Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OTGCam foreground service notification"
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val captureIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CameraService::class.java).apply { action = ACTION_CAPTURE_PHOTO },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("OTGCam Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_camera, "Capture Photo", captureIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private fun broadcastStatus(message: String) {
        addLogEntry(message)
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastLog(message: String) {
        broadcastStatus(message)
    }

    private fun addLogEntry(message: String) {
        val entry = "${System.currentTimeMillis()}|$message"
        if (inMemoryLog.size >= 50) inMemoryLog.removeFirst()
        inMemoryLog.addLast(entry)
        val logIntent = Intent(ACTION_LOG_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(logIntent)
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private fun loadConfig(): AppConfig? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                this,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val token = prefs.getString(KEY_BOT_TOKEN, null) ?: return null
            val chatId = prefs.getString(KEY_CHAT_ID, null) ?: return null
            val agentId = prefs.getString(KEY_AGENT_ID, null) ?: return null
            AppConfig(botToken = token, chatId = chatId, agentId = agentId)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        const val ACTION_START              = "com.otgcam.agent.ACTION_START"
        const val ACTION_STOP               = "com.otgcam.agent.ACTION_STOP"
        const val ACTION_CAPTURE_PHOTO      = "com.otgcam.agent.ACTION_CAPTURE_PHOTO"
        const val ACTION_CAPTURE_VIDEO      = "com.otgcam.agent.ACTION_CAPTURE_VIDEO"
        const val ACTION_TOGGLE_LIVE_AUDIO  = "com.otgcam.agent.ACTION_TOGGLE_LIVE_AUDIO"
        const val ACTION_STATUS_UPDATE      = "com.otgcam.agent.ACTION_STATUS_UPDATE"
        const val ACTION_LOG_UPDATE         = "com.otgcam.agent.ACTION_LOG_UPDATE"
        const val EXTRA_STATUS_MESSAGE      = "status_message"

        private const val NOTIF_ID          = 1001
        private const val NOTIF_CHANNEL_ID  = "otgcam_channel"
        private const val PREFS_NAME        = "otgcam_prefs"
        private const val ENCRYPTED_PREFS_NAME = "otgcam_secure_prefs"
        private const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_BOT_TOKEN             = "bot_token"
        const val KEY_CHAT_ID               = "chat_id"
        const val KEY_AGENT_ID              = "agent_id"
    }
}
