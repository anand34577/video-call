package com.videocall.mobile.call

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack

/** Owns the local mic/camera capture pipeline; one instance per active call. */
class LocalMedia(private val context: Context) {
    var audioTrack: AudioTrack? = null
        private set
    var videoTrack: VideoTrack? = null
        private set
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    var facingFront = true
        private set

    fun start(withVideo: Boolean) {
        val factory = WebRtc.factory
        val audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("mic", audioSource)
        if (withVideo) startVideo()
    }

    fun startVideo() {
        if (videoTrack != null) return
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        facingFront = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("CaptureThread", WebRtc.eglBase.eglBaseContext)
        val videoSource = WebRtc.factory.createVideoSource(cap.isScreencast)
        cap.initialize(helper, context, videoSource.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap
        surfaceHelper = helper
        videoTrack = WebRtc.factory.createVideoTrack("cam", videoSource)
    }

    fun stopVideo() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        videoTrack?.dispose()
        videoTrack = null
    }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(null)
        facingFront = !facingFront
    }

    fun setMic(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun setCam(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
    }

    fun close() {
        stopVideo()
        audioTrack?.dispose()
        audioTrack = null
    }
}
