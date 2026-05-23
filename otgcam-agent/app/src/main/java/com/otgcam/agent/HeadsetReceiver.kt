package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * High-priority BroadcastReceiver that intercepts media button events from both
 * wired earphones and Bluetooth headsets (including AirPods).
 *
 * Only `ACTION_UP` events are forwarded to prevent double-firing on key press.
 * The broadcast is always aborted so no other receiver processes it.
 */
class HeadsetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val keyEvent: KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return

        // Only process the key-up to prevent double dispatch on press + release.
        if (keyEvent.action != KeyEvent.ACTION_UP) {
            abortBroadcast()
            return
        }

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                HeadsetController.handlePress(context)
            }
            // All other keys pass through unhandled.
        }

        abortBroadcast()
    }
}
