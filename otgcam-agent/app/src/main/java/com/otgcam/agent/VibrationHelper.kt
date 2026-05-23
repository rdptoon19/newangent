package com.otgcam.agent

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Centralised haptic feedback helper.
 * All functions use explicit timing arrays — never device-default effects or sounds.
 * API 26+ uses VibrationEffect; API 21-25 uses the deprecated LongArray overload.
 */
object VibrationHelper {

    /**
     * Single 150 ms pulse — fired immediately after photo or video capture confirmed.
     */
    fun vibrateCaptureConfirm(context: Context) {
        val timings = longArrayOf(0L, 150L)
        vibrate(context, timings)
    }

    /**
     * Double pulse (150 ms on, 150 ms off, 150 ms on) — fired on successful upload to Telegram.
     */
    fun vibrateUploadSuccess(context: Context) {
        val timings = longArrayOf(0L, 150L, 150L, 150L)
        vibrate(context, timings)
    }

    /**
     * Triple pulse (150/100/150/100/150 ms) — fired on the Agent when an incoming call
     * is automatically accepted.
     */
    fun vibrateIncomingCallAccepted(context: Context) {
        val timings = longArrayOf(0L, 150L, 100L, 150L, 100L, 150L)
        vibrate(context, timings)
    }

    /**
     * Single 3-second continuous vibration — fired when the UVC camera is attached
     * or detached from the OTG port.
     */
    fun vibrateUvcEvent(context: Context) {
        val timings = longArrayOf(0L, 3000L)
        vibrate(context, timings)
    }

    // ---- internal -------------------------------------------------------

    private fun vibrate(context: Context, timings: LongArray) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
