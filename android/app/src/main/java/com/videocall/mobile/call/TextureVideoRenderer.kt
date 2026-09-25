package com.videocall.mobile.call

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * Draws a WebRTC video track into a TextureView.
 *
 * WebRTC's own SurfaceViewRenderer is a SurfaceView, which ignores clipping
 * and transforms: rounded corners were drawn over a still-rectangular video,
 * and the self-view couldn't be moved smoothly. A TextureView is an ordinary
 * view, so it clips, moves and animates like the rest of the UI.
 *
 * [fill] crops the video to fill the view; otherwise the whole frame is
 * shown with bars (used for shared screens, which shouldn't lose their edges).
 */
class TextureVideoRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("vc-texture")
    private val main = Handler(Looper.getMainLooper())
    private var frameWidth = 0
    private var frameHeight = 0
    private var fill = true

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(sharedContext: EglBase.Context, fill: Boolean) {
        this.fill = fill
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    fun setFill(fill: Boolean) {
        if (this.fill == fill) return
        this.fill = fill
        updateScaling()
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            main.post { updateScaling() }
        }
        renderer.onFrame(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScaling()
    }

    // Fill: EglRenderer crops the frame to the view's shape. Fit: the frame is
    // drawn over the whole surface and the view is scaled back to the video's
    // shape, leaving transparent bars.
    private fun updateScaling() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || frameWidth <= 0 || frameHeight <= 0) return
        val viewAspect = vw / vh
        val frameAspect = frameWidth.toFloat() / frameHeight
        val m = Matrix()
        if (fill) {
            renderer.setLayoutAspectRatio(viewAspect)
        } else {
            renderer.setLayoutAspectRatio(0f)
            if (frameAspect > viewAspect) m.setScale(1f, viewAspect / frameAspect, vw / 2, vh / 2)
            else m.setScale(frameAspect / viewAspect, 1f, vw / 2, vh / 2)
        }
        setTransform(m)
        invalidate()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.createEglSurface(surface)
        updateScaling()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateScaling()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        runCatching { done.await() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() = renderer.release()
}
