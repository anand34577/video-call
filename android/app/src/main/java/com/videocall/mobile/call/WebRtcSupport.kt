package com.videocall.mobile.call

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide WebRTC plumbing: one PeerConnectionFactory + EGL context for
 * the whole app (creating a second factory is expensive and unnecessary —
 * both DirectCall and RoomClient share this one).
 */
object WebRtc {
    lateinit var eglBase: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set
    private var initialized = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
        initialized = true
    }
}

// SdpObserver callbacks also fire from WebRTC's native threads via JNI, same
// as PeerConnection.Observer — resuming a CancellableContinuation twice (e.g.
// success firing after the coroutine that awaited it was already cancelled by
// call teardown) throws IllegalStateException, which would escape back into
// native code and hard-abort the process exactly like an observer-callback
// exception would. Guard every resume with isActive.
private fun <T> kotlinx.coroutines.CancellableContinuation<T>.resumeSafely(value: T) {
    if (isActive) runCatching { resume(value) }
}
private fun kotlinx.coroutines.CancellableContinuation<*>.resumeWithExceptionSafely(error: Throwable) {
    if (isActive) runCatching { resumeWithException(error) }
}

/** Wraps the callback-based SdpObserver in a suspend function. */
suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resumeSafely(sdp)
            override fun onCreateFailure(error: String) = cont.resumeWithExceptionSafely(Exception(error))
        }, constraints)
    }

suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resumeSafely(sdp)
            override fun onCreateFailure(error: String) = cont.resumeWithExceptionSafely(Exception(error))
        }, constraints)
    }

suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = cont.resumeSafely(Unit)
            override fun onSetFailure(error: String) = cont.resumeWithExceptionSafely(Exception(error))
        }, sdp)
    }

suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = cont.resumeSafely(Unit)
            override fun onSetFailure(error: String) = cont.resumeWithExceptionSafely(Exception(error))
        }, sdp)
    }

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}

open class SimplePcObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
}

fun candidateToMap(c: IceCandidate) = mapOf(
    "candidate" to c.sdp,
    "sdpMid" to c.sdpMid,
    "sdpMLineIndex" to c.sdpMLineIndex,
)

fun candidateFromJson(obj: kotlinx.serialization.json.JsonObject): IceCandidate {
    val sdp = (obj["candidate"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    val mid = (obj["sdpMid"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val idx = (obj["sdpMLineIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
    return IceCandidate(mid, idx, sdp)
}
