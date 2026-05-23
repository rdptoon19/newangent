package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Monitors USB device attachment and detachment events at the OS level,
 * independently of the UVC camera library. Provides haptic feedback and
 * broadcasts status messages even before the camera client is initialised.
 */
class UsbMonitor(private val context: Context) {

    companion object {
        /** USB device class code for Video devices (UVC cameras). */
        private const val USB_CLASS_VIDEO = 14

        /** Composite class code; interfaces are examined individually. */
        private const val USB_CLASS_MISC = 239
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUvcCamera(device)) {
                        VibrationHelper.vibrateUvcEvent(context)
                        broadcastStatus(
                            context,
                            "UVC camera attached: ${device.productName ?: device.deviceName}"
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUvcCamera(device)) {
                        VibrationHelper.vibrateUvcEvent(context)
                        broadcastStatus(
                            context,
                            "UVC camera detached: ${device.productName ?: device.deviceName}"
                        )
                    }
                }
            }
        }
    }

    /** Registers the USB broadcast receiver. Call from [CameraService.handleStart]. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
    }

    /** Unregisters the USB broadcast receiver. Safe to call multiple times. */
    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered — safe to ignore.
        }
    }

    /**
     * Returns true if [device] is a UVC video capture device.
     * Checks both the device-level class and individual interface classes.
     */
    private fun isUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_VIDEO) return true
        }
        return false
    }

    // ---- helpers ---------------------------------------------------------

    private fun broadcastStatus(ctx: Context, message: String) {
        val intent = Intent(CameraService.ACTION_STATUS_UPDATE).apply {
            putExtra(CameraService.EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}
