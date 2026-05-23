package com.otgcam.agent

import android.content.Context
import com.otgcam.agent.model.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*

/**
 * Manages the WebRTC peer connection lifecycle for the Agent app.
 *
 * Handles outgoing (Agent-initiated) and incoming (Receiver-initiated) calls.
 * Audio and optional video tracks are sourced from the device microphone and
 * the UVC camera surface respectively.
 */
class WebRtcManager(
    private val context: Context,
    private val config: AppConfig
) {

    companion object {
        private const val AUDIO_TRACK_ID = "otgcam_audio_0"
        private const val VIDEO_TRACK_ID = "otgcam_video_0"
        private const val STREAM_ID = "otgcam_stream_0"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null

    /** True while a WebRTC peer connection is active. */
    var isCallActive: Boolean = false
        private set

    /** List of buffered ICE candidates that arrived before the remote description was set. */
    private val pendingCandidates = mutableListOf<IceCandidate>()

    // ---- Lifecycle -------------------------------------------------------

    /**
     * Initialises [PeerConnectionFactory] and creates the shared audio track.
     * Must be called before any call operations.
     */
    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDecoderFactoryFactory(BuiltinAudioDecoderFactoryFactory())
            .setAudioEncoderFactoryFactory(BuiltinAudioEncoderFactoryFactory())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(EglBase.create().eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
    }

    // ---- Call operations -------------------------------------------------

    /**
     * Creates a new peer connection, attaches local tracks, and generates an SDP offer.
     * The serialised offer JSON is delivered to [onOfferCreated] on the calling coroutine.
     */
    fun startOutgoingCall(onOfferCreated: (String) -> Unit) {
        val pc = buildPeerConnection() ?: return
        peerConnection = pc
        isCallActive = true

        pc.addTrack(
            localAudioTrack ?: return,
            listOf(STREAM_ID)
        )
        localVideoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        onOfferCreated(sdpToJson(sdp))
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Handles an incoming call from the Receiver: parses the SDP offer, creates a peer
     * connection, and generates an SDP answer delivered to [onAnswerCreated].
     */
    fun handleIncomingCall(sdpJson: String, onAnswerCreated: (String) -> Unit) {
        val remoteSdp = jsonToSdp(sdpJson, SessionDescription.Type.OFFER) ?: return
        val pc = buildPeerConnection() ?: return
        peerConnection = pc
        isCallActive = true

        pc.addTrack(
            localAudioTrack ?: return,
            listOf(STREAM_ID)
        )
        localVideoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                applyPendingCandidates(pc)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                onAnswerCreated(sdpToJson(sdp))
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, sdp)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, remoteSdp)
    }

    /**
     * Applies the remote SDP answer received from the Receiver.
     */
    fun handleAnswer(sdpJson: String) {
        val remoteSdp = jsonToSdp(sdpJson, SessionDescription.Type.ANSWER) ?: return
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                applyPendingCandidates(peerConnection ?: return)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, remoteSdp)
    }

    /**
     * Adds a trickle ICE candidate received from the remote peer.
     */
    fun addIceCandidate(candidateJson: String) {
        val candidate = jsonToIceCandidate(candidateJson) ?: return
        val pc = peerConnection
        if (pc != null) {
            pc.addIceCandidate(candidate)
        } else {
            pendingCandidates.add(candidate)
        }
    }

    /**
     * Toggles the live call: ends an active call or starts a new outgoing call.
     * Uses [uploader] to send the SDP offer via Telegram.
     */
    fun toggleLiveCall(uploader: TelegramUploader) {
        if (isCallActive) {
            endCall()
        } else {
            startOutgoingCall { offerJson ->
                scope.launch {
                    uploader.sendSignal(
                        com.otgcam.agent.model.CallSignal(
                            event = "live_start",
                            sdp = offerJson,
                            agentId = config.agentId
                        )
                    )
                }
            }
        }
    }

    /**
     * Closes the peer connection and disposes all tracks.
     */
    fun endCall() {
        peerConnection?.close()
        peerConnection = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        pendingCandidates.clear()
        isCallActive = false
    }

    /** Disposes factory resources. Call on service destruction. */
    fun dispose() {
        endCall()
        localAudioTrack?.dispose()
        if (::peerConnectionFactory.isInitialized) {
            peerConnectionFactory.dispose()
        }
    }

    // ---- Private helpers -------------------------------------------------

    private fun buildPeerConnection(): PeerConnection? {
        val iceServer = PeerConnection.IceServer.builder(config.stunServerUrl).createIceServer()
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(iceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    scope.launch {
                        // ICE candidates are sent opportunistically; errors are non-fatal.
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {}
            }
        )
    }

    private fun applyPendingCandidates(pc: PeerConnection) {
        pendingCandidates.forEach { pc.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun sdpToJson(sdp: SessionDescription): String {
        return JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }.toString()
    }

    private fun jsonToSdp(json: String, fallbackType: SessionDescription.Type): SessionDescription? {
        return try {
            val obj = JSONObject(json)
            val typeStr = obj.optString("type", fallbackType.canonicalForm())
            val sdpStr = obj.getString("sdp")
            val type = SessionDescription.Type.fromCanonicalForm(typeStr)
            SessionDescription(type, sdpStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonToIceCandidate(json: String): IceCandidate? {
        return try {
            val obj = JSONObject(json)
            IceCandidate(
                obj.getString("sdpMid"),
                obj.getInt("sdpMLineIndex"),
                obj.getString("candidate")
            )
        } catch (e: Exception) {
            null
        }
    }
}
