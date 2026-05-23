package com.otgcam.agent

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.otgcam.agent.model.CallSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a Telegram long-poll loop and automatically accepts any incoming call_request
 * signal from the Receiver.
 *
 * - Fires triple-pulse haptic feedback on incoming call.
 * - Generates an SDP answer and sends it back through Telegram.
 * - Applies ICE candidates as they arrive.
 * - Terminates the peer connection on call_end.
 */
class CallAutoAcceptor(
    private val uploader: TelegramUploader,
    private val rtcManager: WebRtcManager,
    private val context: Context
) {

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Begins the long-poll loop. Idempotent — safe to call multiple times. */
    fun startListening() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            uploader.pollForSignals { signal -> handleSignal(signal) }
        }
    }

    /** Cancels the long-poll loop. Idempotent — safe to call multiple times. */
    fun stopListening() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ---- Signal dispatch -------------------------------------------------

    private fun handleSignal(signal: CallSignal) {
        when (signal.event) {
            "call_request" -> {
                VibrationHelper.vibrateIncomingCallAccepted(context)
                broadcastStatus("Incoming call — auto-accepting...")
                rtcManager.handleIncomingCall(signal.sdp ?: "") { answerJson ->
                    scope.launch {
                        uploader.sendSignal(
                            CallSignal(
                                event = "call_answer",
                                sdp = answerJson,
                                agentId = signal.agentId
                            )
                        )
                        broadcastStatus("Live call active.")
                    }
                }
            }
            "call_end" -> {
                rtcManager.endCall()
                broadcastStatus("Call ended by Receiver.")
            }
            "ice_candidate" -> {
                rtcManager.addIceCandidate(signal.candidate ?: "")
            }
        }
    }

    private fun broadcastStatus(message: String) {
        val intent = Intent(CameraService.ACTION_STATUS_UPDATE).apply {
            putExtra(CameraService.EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}
