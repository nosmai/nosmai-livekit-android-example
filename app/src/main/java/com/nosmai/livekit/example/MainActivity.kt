package com.nosmai.livekit.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK
import com.nosmai.livekit.example.databinding.ActivityMainBinding
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase

/**
 * Nosmai + LiveKit on Android, natively.
 *
 * **Nosmai owns the camera and the preview; LiveKit only publishes.**
 *
 * Most of this file is UI. The integration is four things:
 *
 *  1. Create ONE [EglBase] and give it to BOTH — Nosmai (before init) and
 *     LiveKit (via [LiveKitOverrides]). The shared context is what makes the
 *     zero-copy texture handoff possible.
 *  2. Let Nosmai own capture: [NosmaiSDK.startProcessing] against a mounted
 *     [NosmaiPreviewView].
 *  3. Publish a [NosmaiVideoCapturer] as an ordinary LiveKit video track.
 *  4. Never let LiveKit open a camera.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NosmaiLiveKit"
        private const val PERM_REQUEST = 100

        // ── Fill these in ───────────────────────────────────────────────
        // Nosmai licence keys are bound to your applicationId.
        private const val NOSMAI_LICENSE_KEY = "YOUR_NOSMAI_ANDROID_KEY"

        // A device cannot reach your machine over localhost — use your machine's
        // LAN IP, e.g. "ws://192.168.1.42:7880".
        //   livekit-server --dev --bind 0.0.0.0
        //   lk token create --api-key devkey --api-secret secret \
        //     --join --room my-room --identity phone --valid-for 720h
        private const val LIVEKIT_URL = "ws://YOUR_LAN_IP:7880"
        private const val LIVEKIT_TOKEN = "YOUR_JOIN_TOKEN"
    }

    private lateinit var binding: ActivityMainBinding

    /**
     * The ONE EglBase, shared by Nosmai and LiveKit.
     *
     * Creating it ourselves (rather than reading LiveKit's) sidesteps an
     * ordering trap: Nosmai only consumes a share handle while constructing its
     * GL context, so the handle must exist before [NosmaiSDK.initialize]. If we
     * waited for LiveKit to create one on connect, we would be too late — and
     * the failure is silent: Nosmai falls back to a standalone context, its
     * texture ids mean nothing to the encoder, and the remote just shows black.
     */
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var previewView: NosmaiPreviewView? = null
    private var room: Room? = null
    private var capturer: NosmaiVideoCapturer? = null
    private var camera2Helper: Camera2Helper? = null
    private var nosmaiStarted = false
    private var streaming = false
    private var isFrontCamera = true
    private var shareContextOk = false

    /** One of [NosmaiSDK.MIRROR_AUTO] / [NosmaiSDK.MIRROR_ON] / [NosmaiSDK.MIRROR_OFF]. */
    private var mirrorOverride = NosmaiSDK.MIRROR_AUTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.goLiveButton.setOnClickListener {
            if (streaming) stopStreaming() else startStreaming()
        }
        binding.switchCameraButton.setOnClickListener {
            // Camera switching goes through NOSMAI — LiveKit does not own the
            // camera, and its switch APIs would try to open one.
            isFrontCamera = !isFrontCamera
            // The app owns capture, so switching means restarting the helper.
            // startCamera() reports the new facing to the SDK.
            runCatching { camera2Helper?.stopCamera() }
            startCamera()
            status("Camera: ${if (isFrontCamera) "front" else "back"}")
        }

        // OPTIONAL — most apps should delete this button.
        //
        // The display mirror is automatic: front mirrors, back does not, with no
        // call at all, and that survives camera switches. setMirrorOverride is
        // only for an app that wants to hand the user a deliberate toggle.
        //
        // It is DISPLAY ONLY — it does not touch camera facing, so face
        // landmarks and the segmentation mask stay aligned with the real camera
        // whichever way it is set. It is also orthogonal to switching cameras,
        // which is why the Switch button above does not reset it.
        binding.mirrorButton.setOnClickListener {
            mirrorOverride = when (mirrorOverride) {
                NosmaiSDK.MIRROR_AUTO -> NosmaiSDK.MIRROR_ON
                NosmaiSDK.MIRROR_ON -> NosmaiSDK.MIRROR_OFF
                else -> NosmaiSDK.MIRROR_AUTO
            }
            NosmaiSDK.setMirrorOverride(mirrorOverride)

            val label = when (mirrorOverride) {
                NosmaiSDK.MIRROR_ON -> "ON"
                NosmaiSDK.MIRROR_OFF -> "OFF"
                else -> "AUTO"
            }
            binding.mirrorButton.text = "Mirror: $label"
            status("Display mirror: $label")
        }

        buildFilterBar()

        if (hasPermissions()) initNosmai() else requestPermissions()
    }

    // ── Nosmai ───────────────────────────────────────────────────────────

    private fun initNosmai() {
        // STEP 1 — share the EGL context BEFORE initialize(). Order is
        // load-bearing; see the eglBase docs above.
        try {
            // Load the native library explicitly. NosmaiSDK.initialize() would
            // do it, but we are deliberately running before that call, and
            // setAgoraShareContext needs the native binding to already exist.
            System.loadLibrary("nosmai")
            NosmaiSDK.setAgoraShareContext(eglBase.eglBaseContext.nativeEglContext)
            shareContextOk = true
            Log.i(TAG, "EGL share context registered")
        } catch (t: Throwable) {
            // Deliberately not fatal — and that is exactly the trap. Nosmai will
            // build a standalone context, its texture ids will mean nothing to
            // the encoder, and the remote will show black with nothing else
            // appearing to go wrong. So say so loudly instead.
            Log.e(TAG, "EGL share registration FAILED — remote video will be black", t)
        }

        NosmaiSDK.initialize(applicationContext, NOSMAI_LICENSE_KEY)

        // STEP 2 — mount the preview and start the pipeline.
        val pv = NosmaiPreviewView(this)
        previewView = pv
        binding.previewContainer.addView(pv)
        pv.initializePipeline()
        NosmaiSDK.startProcessing(pv)

        // STEP 2b — DRIVE THE CAMERA YOURSELF.
        //
        // This is the part that surprises people coming from the Flutter plugin:
        // startProcessing() does NOT open a camera. The SDK gives you the filter
        // pipeline and a preview surface, but the app owns capture and feeds
        // frames in. (The Flutter plugin hides this by doing it for you.)
        //
        // Camera2Helper is a plain Camera2 wrapper with nothing Nosmai-specific
        // in it — size selection, the 30fps range and sensor orientation.
        // Swap in your own capture if you already have one.
        startCamera()

        nosmaiStarted = true
        status(
            if (shareContextOk) "Nosmai ready — tap Go Live"
            else "Nosmai ready, but the EGL share FAILED — the remote will be black",
        )
    }

    private fun startCamera() {
        val helper = Camera2Helper(this, isFrontCamera)
        camera2Helper = helper
        helper.setInputMode(Camera2Helper.InputMode.YUV)

        // Facing drives the SDK's own processing; orientation drives the
        // preview's display transform (including the selfie mirror).
        NosmaiSDK.setCameraFacing(isFrontCamera)
        applyCameraOrientation(helper)

        // Runs on Camera2's background thread. Hand the planes straight to
        // Nosmai; it does the filtering and drives both the preview and the
        // streaming pass from the same frame.
        helper.setFrameCallback { y, u, v, width, height,
                                  yStride, uStride, vStride,
                                  uPixelStride, vPixelStride ->
            previewView?.processYuvFrame(
                y, u, v, width, height,
                yStride, uStride, vStride,
                uPixelStride, vPixelStride,
                frameRotation(helper),
            )
        }
        helper.startCamera()
        Log.i(TAG, "camera started (front=$isFrontCamera)")
    }

    /**
     * Tell the preview which camera is running, for display orientation.
     *
     * Note there is NO mirror call here. Mirroring is derived from camera facing
     * inside the SDK — [NosmaiSDK.setCameraFacing] above is all it needs, and
     * the render sink reads that per frame. Front is mirrored (selfie
     * convention), back is not.
     */
    private fun applyCameraOrientation(helper: Camera2Helper) {
        previewView?.setCameraOrientation(isFrontCamera, helper.sensorOrientation)
    }

    // ── Filters ──────────────────────────────────────────────────────────

    /** Effects discovered under assets/filters/, in listing order. */
    private val filters = mutableListOf<String>()
    private var selectedFilter: String? = null
    private val filterButtons = mutableMapOf<String?, MaterialButton>()

    /**
     * Discover bundled effects and build the selector from what is actually
     * there.
     *
     * Enumerating assets rather than hardcoding names means dropping a new
     * .nosmai into assets/filters/ is the only step needed to add an effect —
     * no code change, and no button that points at a file that isn't shipped.
     */
    private fun buildFilterBar() {
        filters.clear()
        filters += runCatching {
            assets.list("filters")
                ?.filter { it.endsWith(".nosmai") }
                ?.sorted()
                .orEmpty()
        }.getOrElse {
            Log.e(TAG, "asset listing failed", it)
            emptyList()
        }

        val row = binding.filterRow
        row.removeAllViews()
        filterButtons.clear()

        // "None" first, so clearing is always reachable.
        row.addView(filterChip(null, "None") { selectFilter(null) })
        filters.forEach { asset ->
            row.addView(filterChip(asset, displayName(asset)) { selectFilter(asset) })
        }

        selectedFilter = null
        refreshFilterSelection()
        Log.i(TAG, "discovered ${filters.size} filter(s): $filters")
    }

    private fun filterChip(key: String?, label: String, onTap: () -> Unit): MaterialButton {
        val button = MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        }
        filterButtons[key] = button
        return button
    }

    /** "reindeer_face.nosmai" -> "Reindeer Face" */
    private fun displayName(asset: String) = asset
        .removeSuffix(".nosmai")
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /**
     * Apply an effect, or clear when [asset] is null.
     *
     * applyEffect takes a FILE path, not an asset, so the package is copied out
     * of assets/ into the cache on first use. Effects apply live — change them
     * mid-stream and the remote sees it immediately.
     */
    private fun selectFilter(asset: String?) {
        if (asset == null) {
            runCatching { NosmaiEffects.removeEffect() }
            selectedFilter = null
            refreshFilterSelection()
            status("Filters cleared")
            return
        }
        try {
            val target = java.io.File(externalCacheDir ?: cacheDir, asset)
            if (!target.exists()) {
                assets.open("filters/$asset").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            NosmaiEffects.applyEffect(target.absolutePath, object : NosmaiEffects.EffectCallback {
                override fun onSuccess() {
                    selectedFilter = asset
                    runOnUiThread { refreshFilterSelection() }
                    status("Applied ${displayName(asset)}")
                }

                override fun onError(error: String?) = status("Filter failed: $error")
            })
        } catch (t: Throwable) {
            Log.e(TAG, "selectFilter failed", t)
            status("Filter failed: ${t.message}")
        }
    }

    /** Highlight whichever effect is currently applied. */
    private fun refreshFilterSelection() {
        filterButtons.forEach { (key, button) ->
            button.alpha = if (key == selectedFilter) 1.0f else 0.55f
        }
    }

    /**
     * Rotation Nosmai should apply to the incoming camera frame.
     *
     * Values are the SDK's RotationMode enum (1 = RotateLeft, 2 = RotateRight),
     * not degrees.
     */
    private fun frameRotation(helper: Camera2Helper): Int {
        val sensor = helper.sensorOrientation
        return if (isFrontCamera) {
            when (sensor) {
                90 -> 2
                270 -> 1
                else -> 0
            }
        } else {
            if (sensor == 90) 2 else 1
        }
    }

    // ── LiveKit ──────────────────────────────────────────────────────────

    private fun startStreaming() {
        status("Connecting…")
        lifecycleScope.launch {
            try {
                // STEP 3 — hand LiveKit the SAME EglBase Nosmai is sharing.
                val lkRoom = LiveKit.create(
                    appContext = applicationContext,
                    overrides = LiveKitOverrides(eglBase = eglBase),
                )
                room = lkRoom
                lkRoom.connect(LIVEKIT_URL, LIVEKIT_TOKEN)

                // STEP 4 — publish Nosmai's output as an ordinary video track.
                // No camera is opened here; the capturer is fed by Nosmai.
                val cap = NosmaiVideoCapturer()
                capturer = cap
                val track = lkRoom.localParticipant.createVideoTrack(
                    name = "nosmai",
                    capturer = cap,
                )
                track.startCapture()
                lkRoom.localParticipant.publishVideoTrack(track)

                streaming = true
                runOnUiThread { binding.goLiveButton.text = "Stop" }
                status("Publishing — check a remote viewer")
            } catch (t: Throwable) {
                Log.e(TAG, "startStreaming failed", t)
                status("Failed: ${t.message}")
                stopStreaming()
            }
        }
    }

    /** Teardown order: stop producing, leave the room, then free it. */
    private fun stopStreaming() {
        streaming = false
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { room?.disconnect() }
        // A Room is not reusable — startStreaming() builds a fresh one on every
        // Go Live, so without this each cycle leaks a PeerConnectionFactory and
        // an audio device module.
        //
        // Safe for our shared EglBase: LiveKit only takes ownership of an
        // EglBase it created itself. One handed in via LiveKitOverrides is
        // returned untouched and is never released here.
        runCatching { room?.release() }
        room = null
        runOnUiThread { binding.goLiveButton.text = "Go Live" }
        status("Stopped")
    }

    // ── Lifecycle / plumbing ─────────────────────────────────────────────

    /**
     * Release the camera and pause the GL view while backgrounded.
     *
     * Both halves matter. Another app claiming the camera would otherwise tear
     * ours out from under us, and [NosmaiPreviewView] wraps a GL view whose
     * onPause/onResume the framework does not drive — the Activity has to proxy
     * them, or its render thread keeps running against a surface that is going
     * away.
     *
     * Stop the camera BEFORE pausing the GL view, so no late frame can render
     * into a paused pipeline.
     *
     * Nosmai's own processing and the LiveKit room are deliberately left up. The
     * pipeline is starved rather than stopped, so publishing merely stalls and
     * picks up again on return, and the streaming render mode and texture
     * callback do not have to be re-armed. Nothing renders meanwhile because
     * nothing is feeding it.
     */
    override fun onPause() {
        runCatching { camera2Helper?.stopCamera() }
        camera2Helper = null
        runCatching { previewView?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Nothing to do on the first pass: initNosmai() has already started
        // capture by the time onResume runs, and while permissions are still
        // pending there is nothing to resume.
        if (!nosmaiStarted) return
        runCatching { previewView?.onResume() }
        if (camera2Helper == null) startCamera()
    }

    override fun onDestroy() {
        stopStreaming()
        runCatching { camera2Helper?.stopCamera() }
        camera2Helper = null
        // cleanup() is the full teardown: it clears the frame callbacks, shuts
        // the effects engine down and calls stopProcessing() itself.
        // stopProcessing() on its own deliberately leaves the SDK initialized.
        runCatching { NosmaiSDK.cleanup() }
        runCatching { eglBase.release() }
        super.onDestroy()
    }

    private fun status(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { binding.statusText.text = msg }
    }

    private fun hasPermissions() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() = ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        PERM_REQUEST,
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (hasPermissions()) initNosmai()
            else Toast.makeText(this, "Camera and microphone are required", Toast.LENGTH_LONG).show()
        }
    }
}
