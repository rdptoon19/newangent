package com.otgcam.agent.model

/**
 * Represents a WebRTC signaling message exchanged through Telegram.
 *
 * @property event One of: "call_request", "call_answer", "call_end",
 *                 "live_start", "live_end", "ice_candidate"
 * @property sdp   SDP offer or answer payload (null for non-SDP events)
 * @property candidate ICE candidate JSON string (non-null only for "ice_candidate")
 * @property videoEnabled Whether the call includes a video track
 * @property agentId  Identifies the target Agent device; signals with a mismatched
 *                    agentId are silently discarded
 */
data class CallSignal(
    val event: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val videoEnabled: Boolean = false,
    val agentId: String = ""
)
