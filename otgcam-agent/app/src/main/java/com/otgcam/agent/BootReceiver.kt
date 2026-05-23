package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts [CameraService] after device reboot if the service was running
 * at the time of the last shutdown.
 *
 * Requires the `RECEIVE_BOOT_COMPLETED` permission and must be declared as
 * exported in the manifest with the `BOOT_COMPLETED` intent filter.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(
            CameraService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        if (prefs.getBoolean(CameraService.PREF_SERVICE_RUNNING, false)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
            )
        }
    }
}
