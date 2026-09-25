package com.videocall.mobile.call

import android.content.Context
import android.content.Intent
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack

/** One capture session (device screen -> WebRTC video track) for the life of a screen share. */
class ScreenCapture(private val context: Context, resultCode: Int, permissionIntent: Intent) {
    val track: VideoTrack
    private val capturer: VideoCapturer
    private val helper: SurfaceTextureHelper

    var onEnded: (() -> Unit)? = null

    init {
        WebRtc.ensureInit(context)
        capturer = ScreenCapturerAndroid(permissionIntent, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                onEnded?.invoke()
            }
        })
        helper = SurfaceTextureHelper.create("ScreenCaptureThread", WebRtc.eglBase.eglBaseContext)
        val source = WebRtc.factory.createVideoSource(true)
        capturer.initialize(helper, context, source.capturerObserver)
        val metrics = context.resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)
        track = WebRtc.factory.createVideoTrack("screen", source)
    }

    fun close() {
        runCatching { capturer.stopCapture() }
        capturer.dispose()
        helper.dispose()
        track.dispose()
    }
}
