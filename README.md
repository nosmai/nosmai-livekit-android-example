# Nosmai + LiveKit — native Android example

Publishes a **Nosmai-filtered camera feed** to a LiveKit room from a plain Kotlin
app. AR effects and beauty at full frame rate, with no per-frame copies.

Measured **~30 fps at 720×1280 with AR effects active** on a Pixel 5.

> Using Flutter? See `nosmai_livekit_bridge` instead — it wraps all of this.

---

## The model

```
Camera2 ──► Nosmai (filters + AR) ──┬──► on-screen preview  (NosmaiPreviewView)
                                    └──► LiveKit encoder    (NosmaiVideoCapturer)
```

**Nosmai owns the camera and the preview. LiveKit only encodes and transports.**

LiveKit must never capture. A second capture path means a second Nosmai pipeline
contending for the same GPU — measured at roughly a third of the achievable
frame rate.

### The thing that surprises people

**`NosmaiSDK.startProcessing()` does not open a camera.** The SDK gives you the
filter pipeline and a preview surface; *your app* owns capture and feeds frames
in via `previewView.processYuvFrame(...)`. `Camera2Helper` here does that — it is
plain Camera2 code with nothing Nosmai-specific in it.

---

## Requirements

| | |
|---|---|
| Android Studio | Ladybug or newer (AGP 8.9, Gradle 8.11) |
| JDK | 17 |
| NDK | `29.0.14206865` — what the Nosmai SDK is built with |
| minSdk | 24 (LiveKit's WebRTC floor) |
| ABI | **arm64-v8a only** — the SDK ships one ABI |
| Device | A real arm64 device. The camera and GPU paths do not work on an emulator. |
| Nosmai SDK | `nosmai-release.aar` — from [releases](https://github.com/nosmai/camera-sdk-android/releases) |
| Nosmai licence key | Bound to an `applicationId` — see below |
| LiveKit server | Cloud, self-hosted, or `livekit-server --dev` on your machine |

---

## Setup

**1. Drop in the SDK**

```
app/libs/nosmai-release.aar
```

Download the latest build from the releases page:

**https://github.com/nosmai/camera-sdk-android/releases**

The AAR is not committed here — it is ~36 MB and would go stale the moment a new
SDK build ships, so always take it from releases. Nothing else resolves it, so
the build fails until you drop your copy in.

**2. Set your licence key and applicationId**

Keys are bound to an application id — they must match.

```kotlin
// app/build.gradle.kts
applicationId = "com.your.app"

// MainActivity.kt
private const val NOSMAI_LICENSE_KEY = "YOUR_NOSMAI_ANDROID_KEY"
```

**3. Point at a LiveKit server**

```kotlin
// MainActivity.kt
private const val LIVEKIT_URL   = "ws://YOUR_LAN_IP:7880"
private const val LIVEKIT_TOKEN = "YOUR_JOIN_TOKEN"
```

No cloud account needed for local testing:

```bash
brew install livekit livekit-cli
livekit-server --dev --bind 0.0.0.0

lk token create --api-key devkey --api-secret secret \
  --join --room my-room --identity phone --valid-for 720h
```

`devkey` / `secret` are the fixed credentials `--dev` mode ships with — they are
published defaults, not secrets, and they only exist in dev mode.

Use your machine's **LAN IP**, not `localhost` — a phone cannot reach the host
otherwise. Restarting `--dev` regenerates its signing key, so older tokens start
failing with a bare 401.

**4. Add effects (optional)**

Drop `.nosmai` packages into `app/src/main/assets/filters/`. The selector is
built from whatever is there at runtime — no code change needed. With none
present you get a working stream and a single "None" chip.

---

## Build and run

```bash
./gradlew assembleDebug
./gradlew installDebug     # with a device attached

adb logcat -s NosmaiLiveKit:I NosmaiCapturer:I
```

Or open the project in Android Studio and hit Run.

On launch the app asks for camera and microphone, shows the filtered preview,
and waits. **Go Live** connects and publishes; **Switch** changes camera through
Nosmai; **Mirror** cycles the display-mirror override.

The status line along the bottom is the fastest diagnostic — it reports the EGL
share result, the connection outcome, and every filter change.

---

## How it works

Four steps, all in `MainActivity`:

**1. One `EglBase`, shared by both.**

```kotlin
private val eglBase: EglBase by lazy { EglBase.create() }

System.loadLibrary("nosmai")                                              // first!
NosmaiSDK.setAgoraShareContext(eglBase.eglBaseContext.nativeEglContext)   // before initialize!
NosmaiSDK.initialize(context, licenseKey)
...
LiveKit.create(appContext, overrides = LiveKitOverrides(eglBase = eglBase))
```

Creating it ourselves avoids an ordering trap: Nosmai only consumes a share
handle while constructing its GL context, so the handle must exist **before**
`initialize()`. Get this wrong and Nosmai falls back to a standalone context, its
texture ids mean nothing to the encoder, and **the remote shows black with no
error anywhere**.

(The API is named for Agora for historical reasons. The native side is generic —
it is just the share argument to `eglCreateContext`.)

**2. Nosmai owns capture.** `startProcessing(previewView)` plus a `Camera2Helper`
feeding `processYuvFrame`.

**3. Publish a `NosmaiVideoCapturer`.**

```kotlin
val track = room.localParticipant.createVideoTrack("nosmai", capturer)
track.startCapture()
room.localParticipant.publishVideoTrack(track)
```

`createVideoTrack` accepts a custom `VideoCapturer`, so this needs no reflection
and no LiveKit internals.

**4. Camera switching goes through Nosmai**, never LiveKit.

### The frame path

Nosmai renders each filtered frame into a GL texture and hands over its id.
`NosmaiVideoCapturer` blits it **once** into a texture owned by LiveKit's GL
thread — valid because both are in the same EGL share group — and pushes it to
the capturer observer. No readback, no colour conversion, no CPU copies.

The blit is not gratuitous: the encoder may hold a frame across several vsyncs
while Nosmai wants its ring slot back immediately, so copying decouples the two
lifetimes.

### The files

| File | What it is |
|---|---|
| `MainActivity.kt` | The integration. Mostly UI; the four steps above are the point. |
| `NosmaiVideoCapturer.kt` | A LiveKit `VideoCapturer` fed by Nosmai instead of a camera. Copy this. |
| `Camera2Helper.java` | Plain Camera2 capture. Nothing Nosmai-specific — swap in your own. |

---

## Gotchas

**Order is load-bearing at startup.** `System.loadLibrary("nosmai")`, then
`setAgoraShareContext()`, then `NosmaiSDK.initialize()`. Two ways to get this
wrong, both silent:

- Skip the explicit `loadLibrary` and `setAgoraShareContext` cannot bind, because
  `NosmaiSDK.initialize()` is what would otherwise load the native library — and
  that runs too late.
- Call `setAgoraShareContext` after the GL context already exists and it is
  ignored outright.

Either way Nosmai gets a standalone context and **the remote shows black with no
error**. The app surfaces the failure in its status line for exactly this reason.

**Two native gates.** Streaming needs *both* `setRenderMode(DUAL_OUTPUT)` and a
registered `setTextureFrameCallback`. Arming only one yields **zero frames with
no error** — indistinguishable from a broken callback.

**`releaseStreamSlot` exactly once per frame.** Miss one and Nosmai's producer
ring starves; the stream freezes silently.

**Never let LiveKit open a camera.** If you see roughly a third of the expected
frame rate, that is what happened.

**Mirroring is automatic.** The SDK derives it from camera facing — front
mirrors, back does not — and that survives camera switches, so there is no
mirror call to make. `setMirrorOverride()` exists only for an app that wants to
give the user a deliberate toggle; it is display-only and leaves face landmarks
aligned with the real camera. The Mirror button demonstrates it. Most apps
should delete that button.

**Proxy the preview view's lifecycle.** `NosmaiPreviewView.onPause()` /
`onResume()` are not driven by the framework — the Activity has to forward them,
and release the camera on pause, or another app claiming the camera tears yours
out mid-session.

**A `Room` is single-use.** `disconnect()` then `release()`, and build a new one
next time. LiveKit only releases an `EglBase` it created itself, so a shared one
passed through `LiveKitOverrides` survives `release()` untouched.

**arm64 only.** The SDK ships one ABI; `abiFilters` is set accordingly.

**OpenCL needs declaring.** The `<uses-native-library>` entries in the manifest
are required from Android 12 — without them the SDK silently falls back to CPU.

---

## Checking the stream

`nosmai_livekit_bridge/tools/livekit-viewer/` is a minimal browser viewer that
renders the published stream without cropping and reports live resolution,
orientation, fps and codec. Serve it over plain `http://` — `ws://` is blocked as
mixed content from an `https://` page, which also rules out the hosted LiveKit
Meet demo against a local dev server.

---

## Licence

MIT — see [LICENSE](LICENSE). The Nosmai SDK itself is licensed separately.
