package com.otgcam.agent

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Singleton tap-detection state machine for wired and Bluetooth earpiece buttons.
 *
 * Counts `ACTION_UP` events within a 600 ms window and dispatches the appropriate
 * action to [CameraService]:
 *
 * - 1 tap → capture photo
 * - 2 taps → capture 30-second video
 * - 3 taps → toggle live call
 */
object HeadsetController {

    /** Milliseconds between the first tap and final dispatch evaluation. */
    private const val TAP_WINDOW_MS = 600L

    private var tapCount: Int = 0
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /**
     * Called by [HeadsetReceiver] for every qualifying `ACTION_UP` key event.
     * Accumulates taps within the window and dispatches the resolved action.
     */
    fun handlePress(context: Context) {
        tapCount++
        pendingRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            val count = tapCount
            tapCount = 0
            when (count) {
                1 -> dispatchToService(context, CameraService.ACTION_CAPTURE_PHOTO)
                2 -> dispatchToService(context, CameraService.ACTION_CAPTURE_VIDEO)
                3 -> dispatchToService(context, CameraService.ACTION_TOGGLE_LIVE_AUDIO)
                // 4+ taps ignored
            }
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, TAP_WINDOW_MS)
    }

    /** Sends an action intent to [CameraService] via the foreground service channel. */
    private fun dispatchToService(context: Context, action: String) {
        val intent = Intent(context, CameraService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
