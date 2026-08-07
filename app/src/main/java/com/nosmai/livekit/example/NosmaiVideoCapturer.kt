package com.nosmai.livekit.example

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.nosmai.effect.api.NosmaiSDK
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.TextureBufferImpl
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.YuvConverter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A LiveKit [VideoCapturer] whose frames come from Nosmai instead of a camera.
 *
 * Hand this to `LocalParticipant.createVideoTrack(name, capturer)` and LiveKit
 * treats it like any other source — it supplies the [SurfaceTextureHelper] and
 * [CapturerObserver] in [initialize], and we push frames into the observer.
 *
 * ## Why Nosmai owns the camera
 *
 * This capturer never opens a camera. Nosmai already has it, and opening a
 * second one would spin up a second Nosmai pipeline contending for the same
 * GPU — measured at roughly a third of the achievable frame rate.
 *
 * ## The frame path (one GPU blit, nothing else)
 *
 * Nosmai renders a filtered frame into a texture and gives us its id. We blit
 * that once into a texture owned by LiveKit's GL thread — valid because both
 * contexts are in the same EGL share group — and hand it to the observer. No
 * readback, no colour conversion, no CPU copies.
 *
 * The blit is not gratuitous: the encoder may hold a frame across several
 * vsyncs, while Nosmai wants its ring slot back immediately. Copying decouples
 * the two lifetimes.
 */
class NosmaiVideoCapturer : VideoCapturer {

    companion object {
        private const val TAG = "NosmaiCapturer"
        private const val RING = 3
        private const val SLOT_STALL_MS = 500L
    }

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var yuvConverter: YuvConverter? = null

    private val tex = IntArray(RING)
    private val fbo = IntArray(RING)
    private val busy = Array(RING) { AtomicBoolean(false) }
    private val busySince = LongArray(RING)
    private var srcFbo = 0
    private var ringW = 0
    private var ringH = 0

    // Single-flight: if the previous push is still in flight we DROP rather than
    // queue. Backing up would stall Nosmai's render thread, and latency there is
    // worse than a dropped frame.
    private val inFlight = AtomicBoolean(false)

    @Volatile private var capturing = false
    private var pushCount = 0
    private var dropCount = 0

    /**
     * Rotation stamped on outgoing frames.
     *
     * 0 is correct: Nosmai's streaming pass already renders into an upright
     * portrait target, so a non-zero value ADDS a rotation rather than
     * correcting one. Carried as metadata and applied by the receiver, so it is
     * free to change.
     */
    @Volatile var rotationDegrees: Int = 0

    // ── VideoCapturer ────────────────────────────────────────────────────

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
        // YuvConverter allocates GL objects, so build it ON the helper thread.
        // TextureBufferImpl requires one, though it is only exercised if
        // something downstream actually asks for I420.
        surfaceTextureHelper.handler.post {
            runCatching { yuvConverter = YuvConverter() }
                .onFailure { Log.e(TAG, "YuvConverter init failed", it) }
        }
        Log.i(TAG, "initialized")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        capturing = true
        observer?.onCapturerStarted(true)

        // TWO INDEPENDENT NATIVE GATES. Arming only one yields zero frames with
        // no error, which is indistinguishable from a broken callback:
        //   1) the render mode must permit a streaming pass
        //   2) the texture callback must be registered
        // DUAL_OUTPUT rather than STREAMING_ONLY because Nosmai owns the VISIBLE
        // preview in this model; STREAMING_ONLY would blank it.
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
        NosmaiSDK.setTextureFrameCallback { texId, w, h, tsNs, _ ->
            // Runs on Nosmai's render thread — never block here.
            if (!capturing) {
                NosmaiSDK.releaseStreamSlot(texId)
            } else {
                pushFrame(texId, w, h, tsNs)
            }
        }
        Log.i(TAG, "capture started (${width}x$height@$framerate requested)")
    }

    override fun stopCapture() {
        // Stop PRODUCING first: tearing the consumer down under a live callback
        // races a frame already in flight.
        capturing = false
        runCatching { NosmaiSDK.setTextureFrameCallback(null) }
        runCatching { NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY) }
        observer?.onCapturerStopped()
        releaseRing()
        Log.i(TAG, "capture stopped (pushed $pushCount, dropped $dropCount)")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Nosmai decides the streaming resolution; nothing to renegotiate.
    }

    override fun dispose() {
        stopCapture()
        runCatching { yuvConverter?.let { c -> helper?.handler?.post { c.release() } } }
        yuvConverter = null
        helper = null
        observer = null
    }

    override fun isScreencast(): Boolean = false

    // ── Frame path ───────────────────────────────────────────────────────

    private fun pushFrame(srcTexId: Int, width: Int, height: Int, timestampNs: Long) {
        val sth = helper
        val obs = observer
        if (sth == null || obs == null) {
            NosmaiSDK.releaseStreamSlot(srcTexId)
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            dropCount++
            NosmaiSDK.releaseStreamSlot(srcTexId)
            return
        }

        // Hop to LiveKit's GL thread: its context is the one the encoder can
        // read from.
        sth.handler.post {
            try {
                ensureRing(width, height)
                val slot = acquireSlot()
                if (slot < 0) {
                    if (dropCount++ % 60 == 0) Log.w(TAG, "ring busy, dropping frame")
                    return@post
                }

                if (srcFbo == 0) {
                    val f = IntArray(1)
                    GLES20.glGenFramebuffers(1, f, 0)
                    srcFbo = f[0]
                }

                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, srcTexId, 0
                )
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[slot])
                // Y-FLIPPED (dst y range reversed): GL textures are bottom-up,
                // WebRTC expects top-down.
                GLES30.glBlitFramebuffer(
                    0, 0, width, height,
                    0, height, width, 0,
                    GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST
                )
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, 0, 0
                )
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                GLES20.glFlush()

                val buffer = TextureBufferImpl(
                    width, height,
                    VideoFrame.TextureBuffer.Type.RGB,
                    tex[slot],
                    Matrix(),
                    sth.handler,
                    yuvConverter,
                    { busySince[slot] = 0; busy[slot].set(false) }
                )
                val frame = VideoFrame(buffer, rotationDegrees, timestampNs)
                try {
                    obs.onFrameCaptured(frame)
                    if (++pushCount % 60 == 0) {
                        Log.i(TAG, "pushed $pushCount frames (${width}x$height), dropped $dropCount")
                    }
                } finally {
                    // Release OUR reference; the encoder retains its own if it
                    // needs the frame longer, and the slot frees via the callback.
                    frame.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pushFrame failed", t)
            } finally {
                inFlight.set(false)
                // MUST run exactly once per frame, or Nosmai's producer ring
                // starves and the stream freezes with no error. Lock-free and
                // GL-free by design, so it is safe from this thread.
                NosmaiSDK.releaseStreamSlot(srcTexId)
            }
        }
    }

    private fun ensureRing(width: Int, height: Int) {
        if (ringW == width && ringH == height && tex[0] != 0) return
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(RING, tex, 0)
            GLES20.glDeleteFramebuffers(RING, fbo, 0)
        }
        GLES20.glGenTextures(RING, tex, 0)
        GLES20.glGenFramebuffers(RING, fbo, 0)
        for (i in 0 until RING) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[i], 0
            )
            busy[i].set(false)
            busySince[i] = 0
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ringW = width
        ringH = height
        Log.i(TAG, "ring created ${width}x$height")
    }

    /**
     * Reclaim a slot the encoder never released. Without this a single dropped
     * release permanently shrinks the ring until the stream stalls.
     */
    private fun acquireSlot(): Int {
        val now = android.os.SystemClock.uptimeMillis()
        for (i in 0 until RING) {
            if (busy[i].compareAndSet(false, true)) { busySince[i] = now; return i }
        }
        var oldest = -1
        var oldestSince = Long.MAX_VALUE
        for (i in 0 until RING) {
            val since = busySince[i]
            if (since != 0L && (now - since) > SLOT_STALL_MS && since < oldestSince) {
                oldestSince = since
                oldest = i
            }
        }
        if (oldest >= 0) {
            Log.w(TAG, "reclaiming stalled slot $oldest (${now - oldestSince}ms)")
            busySince[oldest] = now
            return oldest
        }
        return -1
    }

    private fun releaseRing() {
        helper?.handler?.post {
            if (tex[0] != 0) {
                GLES20.glDeleteTextures(RING, tex, 0)
                GLES20.glDeleteFramebuffers(RING, fbo, 0)
                for (i in 0 until RING) { tex[i] = 0; fbo[i] = 0; busy[i].set(false) }
            }
            if (srcFbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(srcFbo), 0)
                srcFbo = 0
            }
            ringW = 0
            ringH = 0
        }
    }
}
