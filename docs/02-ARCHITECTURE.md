# 02 — Architecture

Single Gradle module `:app`, application id and root package `com.voidlink.android`.
Kotlin only. Compose UI. No dependency injection framework — a hand-written
`AppContainer` singleton is enough and keeps build times down.

---

## 1. Layer model

```
┌──────────────────────────────────────────────────────────────────────┐
│  ui/            Compose screens, components, theme, viewmodels       │
│                 Knows about: domain models, StreamSession interface   │
│                 Knows NOTHING about: sockets, XML, byte layouts       │
├──────────────────────────────────────────────────────────────────────┤
│  domain/        Pure Kotlin models + use-cases. No Android imports.   │
├──────────────────────────────────────────────────────────────────────┤
│  data/          Repositories, persistence (DataStore/Room), caches    │
├──────────────────────────────────────────────────────────────────────┤
│  protocol/      NVHTTP, pairing, crypto, RTSP, control, video, audio, │
│                 input. Pure-ish Kotlin; Android only for MediaCodec / │
│                 AudioTrack bindings, which live in platform/.         │
├──────────────────────────────────────────────────────────────────────┤
│  platform/      MediaCodec, AudioTrack, sensors, vibration, sockets,  │
│                 discovery, foreground service.                        │
└──────────────────────────────────────────────────────────────────────┘
```

**The one rule that matters:** `protocol/` must not import `androidx.*` or reference Compose.
Everything in `protocol/` should be unit-testable on the JVM without an emulator. Where a
protocol component needs a platform capability (decode a frame, play audio), it depends on an
**interface declared in `protocol/`** that `platform/` implements.

---

## 2. Package layout

```
com.voidlink.android
│
├── VoidLinkApp.kt                  Application; builds AppContainer
├── AppContainer.kt                 Manual DI: singletons + factories
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt                Token values, light + dark palettes
│   │   ├── Type.kt                 Type scale
│   │   ├── Shape.kt                Corner radii tokens
│   │   ├── Dimens.kt               Spacing + size tokens
│   │   └── VoidLinkTheme.kt        MaterialTheme wrapper + LocalVoidTokens
│   │
│   ├── components/
│   │   ├── SettingsRow.kt          Base row scaffold (label, value, info, content)
│   │   ├── SliderRow.kt
│   │   ├── SegmentedRow.kt
│   │   ├── ToggleRow.kt
│   │   ├── StepperRow.kt
│   │   ├── NavigationRow.kt        Row that opens a subscreen
│   │   ├── InfoButton.kt           Circled-i + popover
│   │   ├── SectionHeader.kt        Collapsible section header w/ glyph + chevron
│   │   ├── HostCard.kt
│   │   ├── AppCard.kt
│   │   ├── StatusPill.kt           Online/Offline indicator
│   │   └── EmptyState.kt
│   │
│   ├── hosts/
│   │   ├── HostsScreen.kt
│   │   ├── HostsViewModel.kt
│   │   ├── AddHostDialog.kt
│   │   └── PairingDialog.kt        Shows PIN, progress, outcome
│   │
│   ├── apps/
│   │   ├── AppGridScreen.kt
│   │   └── AppGridViewModel.kt
│   │
│   ├── settings/
│   │   ├── SettingsPanel.kt        The 340dp sidebar/drawer
│   │   ├── SettingsViewModel.kt
│   │   └── sections/
│   │       ├── VideoSection.kt
│   │       ├── TouchControllerSection.kt
│   │       ├── GesturesSection.kt
│   │       ├── AudioSection.kt
│   │       └── DisplaySection.kt
│   │
│   ├── stream/
│   │   ├── StreamActivity.kt       Separate Activity; owns the SurfaceView
│   │   ├── StreamScreen.kt         Compose overlay layer on top of the surface
│   │   ├── StreamViewModel.kt
│   │   ├── overlay/
│   │   │   ├── StreamOverlay.kt        Stats chip, connection warning, toasts
│   │   │   ├── InStreamSettings.kt     The in-stream settings drawer
│   │   │   ├── OnScreenControls.kt     Virtual gamepad
│   │   │   ├── OnScreenControlLayouts.kt  Off / Simple / Full presets
│   │   │   └── TouchpadOverlay.kt      Touchpad / native-touch / absolute-touch input surface
│   │   └── GestureRecognizers.kt
│   │
│   └── navigation/
│       └── VoidLinkNavHost.kt
│
├── domain/
│   ├── model/
│   │   ├── Host.kt                 Host, HostState, PairState
│   │   ├── AppEntry.kt
│   │   ├── StreamSettings.kt       The full settings object (global or per-host)
│   │   ├── VideoCodec.kt, TouchMode.kt, GyroMode.kt, WidgetSet.kt, ...
│   │   └── StreamStats.kt
│   └── usecase/
│       ├── DiscoverHosts.kt
│       ├── RefreshHostState.kt
│       ├── PairHost.kt
│       ├── LoadAppList.kt
│       ├── LaunchApp.kt
│       ├── QuitApp.kt
│       ├── WakeHost.kt
│       └── ResolveEffectiveSettings.kt    global + per-host override merge
│
├── data/
│   ├── HostRepository.kt
│   ├── SettingsRepository.kt
│   ├── BoxArtCache.kt
│   ├── db/                         Room: HostEntity, HostSettingsEntity, DAOs
│   └── prefs/                      DataStore for global settings
│
├── protocol/
│   ├── ProtocolConstants.kt        Ports, magics, flags, timeouts — ALL of them, one place
│   ├── ByteIO.kt                   Explicit LE/BE readers & writers
│   │
│   ├── identity/
│   │   ├── ClientIdentity.kt       Cert + key + uniqueId
│   │   └── IdentityStore.kt
│   │
│   ├── http/
│   │   ├── NvHttpClient.kt         The 8 endpoints
│   │   ├── ServerInfo.kt           Parsed /serverinfo
│   │   ├── XmlResponse.kt          Envelope parsing + status_code checks
│   │   └── PinnedTls.kt            Client-cert KeyManager + pinned TrustManager
│   │
│   ├── pairing/
│   │   ├── PairingEngine.kt        The 5 phases
│   │   └── PairingCrypto.kt        saltPin, AES-ECB zero-pad, hash selection
│   │
│   ├── rtsp/
│   │   ├── RtspClient.kt           OPTIONS/DESCRIBE/SETUP/PLAY
│   │   ├── RtspMessage.kt          Serialize/parse
│   │   ├── SdpBuilder.kt           The ANNOUNCE attribute set
│   │   └── SdpParser.kt            surround-params extraction
│   │
│   ├── enet/
│   │   ├── EnetHost.kt             Minimal ENet client
│   │   ├── EnetPeer.kt
│   │   ├── EnetProtocol.kt         Command structs
│   │   └── EnetChannel.kt          Reliable sequencing + ACKs
│   │
│   ├── control/
│   │   ├── ControlStream.kt        Start A/B, ping, IDR, termination, feedback dispatch
│   │   ├── ControlMessageTypes.kt  The per-generation type tables
│   │   └── ControlCrypto.kt        AES-GCM framing (phase 2+)
│   │
│   ├── video/
│   │   ├── VideoReceiver.kt        Socket + ping thread
│   │   ├── RtpParser.kt
│   │   ├── NvVideoPacket.kt
│   │   ├── FrameAssembler.kt       FEC block bookkeeping + reassembly
│   │   ├── ReedSolomon.kt          (phase 6; interface + no-op impl first)
│   │   └── DecodeUnit.kt
│   │
│   ├── audio/
│   │   ├── AudioReceiver.kt
│   │   ├── OpusConfig.kt           Multistream config + channel remap
│   │   └── AudioFecBlock.kt
│   │
│   ├── input/
│   │   ├── InputSender.kt          Batching, queueing, encryption
│   │   ├── InputCrypto.kt          GCM / CBC + IV strategy
│   │   ├── InputPackets.kt         Every packet builder from spec §10.3
│   │   ├── ButtonFlags.kt
│   │   └── KeyCodeMap.kt           Android keycode → Windows VK
│   │
│   └── session/
│       ├── StreamSession.kt        THE interface the UI talks to
│       ├── StreamSessionImpl.kt    Orchestrates everything below
│       ├── SessionConfig.kt
│       └── SessionState.kt         The state machine states + events
│
└── platform/
    ├── discovery/
    │   ├── MdnsDiscovery.kt        NsdManager + MulticastLock
    │   └── ManualProbe.kt
    ├── decode/
    │   ├── VideoDecoder.kt         Interface declared in protocol/, impl here
    │   ├── MediaCodecVideoDecoder.kt
    │   └── DecoderProbe.kt         Codec capability enumeration
    ├── audio/
    │   ├── AudioPlayer.kt
    │   └── MediaCodecOpusPlayer.kt
    ├── input/
    │   ├── ControllerManager.kt    InputManager hotplug → arrival/removal
    │   ├── ControllerMapper.kt     MotionEvent/KeyEvent → protocol state
    │   ├── MotionSensors.kt        Gyro/accel, rate-limited
    │   └── RumbleSink.kt
    ├── net/
    │   ├── UdpSocketFactory.kt     Buffer sizes, traffic class
    │   └── WakeOnLan.kt
    └── service/
        ├── StreamingService.kt     Foreground service, wake/wifi locks
        └── StreamNotification.kt
```

---

## 3. Threading model

Latency is the product. Thread discipline is how we get it.

| Thread / dispatcher | Owner | Work | Never does |
|---|---|---|---|
| **Main** | Android | Compose recomposition, Activity lifecycle, `SurfaceHolder` callbacks | Any network, crypto, or file I/O |
| **`Dispatchers.IO`** | coroutines | NVHTTP calls, pairing, RTSP handshake, box-art fetches, DB/DataStore | Nothing in the per-frame hot path |
| **`video-rx`** (dedicated `Thread`, `Process.THREAD_PRIORITY_URGENT_AUDIO`) | `VideoReceiver` | Blocking `DatagramSocket.receive()` → parse → hand to `FrameAssembler` | Decoding, allocation of new buffers |
| **`video-ping`** | `VideoReceiver` | 500 ms keep-alive on the same socket | Anything else |
| **`audio-rx`** (dedicated, urgent-audio priority) | `AudioReceiver` | Receive → FEC block → dequeue in order → hand to `AudioPlayer` | Decoding |
| **`audio-ping`** | `AudioReceiver` | 500 ms keep-alive | |
| **`decoder-cb`** (`HandlerThread`) | `MediaCodecVideoDecoder` | `MediaCodec` async callbacks; `releaseOutputBuffer(true)` | Blocking waits |
| **`audio-dec`** (`HandlerThread`) | `MediaCodecOpusPlayer` | Opus decode callbacks → `AudioTrack.write` | |
| **`enet-io`** (dedicated) | `EnetHost` | ENet service loop: receive, ACK, retransmit, dispatch inbound control messages | Long work in message handlers — those post to `control-work` |
| **`control-work`** (single-thread executor) | `ControlStream` | Periodic ping, loss/FEC stats, IDR requests, host-feedback callbacks | Socket reads |
| **`input-tx`** (single-thread executor) | `InputSender` | Drain the batched input queue, encrypt, send via ENet | Blocking |

**Rules:**

1. **Two queues, both bounded, both drop-oldest under pressure:**
   * `FrameAssembler → VideoDecoder`: capacity **2** decode units. If full, drop the oldest and
     request an IDR. Never grow this queue "for smoothness".
   * `InputSender` queue: capacity 256 events; if full, drop coalescible events (moves) first.
2. **Nothing in the receive path allocates.** Pre-allocate a pool of `ByteArray`/
   `DirectByteBuffer` of `packetSize + 64` and recycle. Measure with allocation tracking.
3. **`enet-io` is the only thread that touches the ENet socket.** All sends are posted to it
   through a lock-free queue. ENet state is not thread-safe.
4. **Cancellation:** every long-lived thread checks an `@Volatile var running` flag and is
   interrupted on teardown. Sockets are closed to unblock `receive()`.
5. Coroutine scopes: `viewModelScope` for UI work; a single `SupervisorJob`-backed
   `sessionScope` (created on session start, cancelled on teardown) for anything session-lived.

---

## 4. The session state machine

This is the heart of the app. One state machine, one owner (`StreamSessionImpl`), exposed to
the UI as a `StateFlow<SessionState>`.

```
                    ┌──────┐
                    │ IDLE │
                    └──┬───┘
                       │ connect(host)
                       ▼
              ┌──────────────────┐
              │ QUERYING_SERVER  │  GET /serverinfo (http, then https if paired)
              └───┬──────────┬───┘
       not paired │          │ paired
                  ▼          ▼
          ┌──────────┐  ┌──────────────┐
          │ PAIRING  │  │ LOADING_APPS │  GET /applist (https)
          │ (5 phases)│ └──────┬───────┘
          └────┬─────┘         │
   PIN_WRONG / │ PAIRED        │ apps loaded
   FAILED /    └───────────────┤
   IN_PROGRESS                 ▼
        │              ┌──────────────┐
        ▼              │  APPS_READY  │◀────────────────┐
   ┌─────────┐         └──────┬───────┘                 │
   │ ERROR   │                │ launch(app) / resume    │
   └─────────┘                ▼                         │
                      ┌───────────────┐                 │
                      │   LAUNCHING   │ GET /launch|/resume
                      └───────┬───────┘                 │
                              │ gamesession != 0        │
                              ▼                         │
                      ┌───────────────┐                 │
                      │  NEGOTIATING  │ RTSP: OPTIONS→DESCRIBE→SETUP×3→ANNOUNCE→PLAY×2
                      └───────┬───────┘                 │
                              │ PLAY 200                │
                              ▼                         │
                      ┌───────────────┐                 │
                      │  CONNECTING   │ ENet connect, Start A/B,
                      │               │ open UDP sockets, start pings,
                      │               │ configure MediaCodec + AudioTrack
                      └───────┬───────┘                 │
                              │ first complete frame decoded
                              ▼                         │
                      ┌───────────────┐                 │
                 ┌───▶│   STREAMING   │                 │
                 │    └───┬───────┬───┘                 │
     reconnect?  │        │       │ user disconnect /   │
     (v1: no)    │        │       │ host termination /  │
                 │        │       │ fatal error         │
                 │        │       ▼                     │
                 │        │  ┌──────────────┐           │
                 │        │  │ TERMINATING  │ §9.7 order│
                 │        │  └──────┬───────┘           │
                 │        │         │ clean             │
                 │        │         └───────────────────┘
                 │        │ transient decoder error
                 └────────┘ (flush + IDR request, stay STREAMING)
```

### 4.1 State definitions

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data class QueryingServer(val host: Host) : SessionState
    data class Pairing(val pin: String, val phase: PairPhase) : SessionState
    data class LoadingApps(val host: Host) : SessionState
    data class AppsReady(val host: Host, val apps: List<AppEntry>, val runningAppId: Long?) : SessionState
    data class Launching(val app: AppEntry, val progress: LaunchStep) : SessionState
    data class Negotiating(val step: RtspStep) : SessionState
    data class Connecting(val step: ConnectStep) : SessionState
    data class Streaming(val stats: StreamStats, val quality: ConnQuality) : SessionState
    data class Terminating(val reason: TerminationReason) : SessionState
    data class Error(val kind: ErrorKind, val detail: String, val recoverable: Boolean) : SessionState
}
```

The sub-step enums exist so the UI can show a meaningful progress line
("Starting session…", "Negotiating video…", "Waiting for first frame…") instead of a bare
spinner. Every transition is logged with a timestamp; the deltas are the first thing we look
at when connection takes too long.

### 4.2 Transition rules

* **Every transition happens on `control-work`**, never on an arbitrary caller thread. The
  `StateFlow` emission is what crosses back to the UI.
* **Errors are terminal for the session** except:
  * A transient `MediaCodec.CodecException` ⇒ flush the codec, request an IDR, stay
    `Streaming`.
  * A frame reassembly failure ⇒ request IDR, stay `Streaming`.
* **`ERROR` carries a `recoverable` flag** that decides whether the UI offers "Retry".
* **Timeouts** (each produces a distinct `ErrorKind`):
  | Step | Timeout |
  |---|---|
  | `/serverinfo` probe | 1 s (believed-offline) / 5 s (believed-online) |
  | Pairing phase 1 | none (user must type the PIN) |
  | Other pairing phases | 10 s |
  | `/launch`, `/resume` | 60 s |
  | RTSP whole handshake | 15 s |
  | ENet connect | 10 s |
  | First video traffic | 10 s ⇒ `NO_VIDEO_TRAFFIC` |
  | First complete frame | 10 s after first traffic ⇒ `NO_VIDEO_FRAME` |

* **Reconnect is out of scope for v1.** If the stream drops, we tear down and return to
  `AppsReady` with an error banner. (`/serverinfo` will show `currentgame != 0`, so the user
  can Resume with one tap — which is the same thing, honestly.)

---

## 5. The UI ↔ protocol interface

The UI never sees a socket, a `ByteBuffer`, or an XML document. It sees these three interfaces.

### 5.1 `HostRepository` — the launcher side

```kotlin
interface HostRepository {
    /** Saved hosts, live-updating as discovery and polling refine them. */
    val hosts: StateFlow<List<Host>>

    fun startDiscovery()
    fun stopDiscovery()

    /** Manual add. Probes /serverinfo; returns the resolved host or an error. */
    suspend fun addManualHost(address: String, port: Int = 47989): Result<Host>

    suspend fun refresh(hostId: String): Result<Host>
    suspend fun forget(hostId: String)
    suspend fun wake(hostId: String): Result<Unit>

    /** Emits pairing progress; completes with the terminal PairResult. */
    fun pair(hostId: String): Flow<PairProgress>
    suspend fun unpair(hostId: String)

    suspend fun loadApps(hostId: String): Result<List<AppEntry>>
    suspend fun boxArt(hostId: String, appId: Long): Result<ImageBitmap>
    suspend fun quitRunningApp(hostId: String): Result<Unit>
}
```

```kotlin
data class Host(
    val id: String,              // the host's <uniqueid>; primary key
    val name: String,
    val address: String,         // last known reachable address
    val httpPort: Int,
    val httpsPort: Int,
    val macAddress: String?,     // null when unknown (Sunshine over plaintext)
    val online: Boolean,
    val paired: Boolean,
    val runningAppId: Long?,     // non-null => a session is live on the host
    val serverKind: ServerKind,  // NVIDIA_GFE | SUNSHINE_FAMILY | UNKNOWN
    val appVersion: String,
    val capabilities: HostCapabilities,   // hevc, hdr, av1, yuv444, nativeTouch, ...
    val lastSeenEpochMs: Long,
)

sealed interface PairProgress {
    data class PinReady(val pin: String) : PairProgress   // show this to the user NOW
    data class Phase(val n: Int) : PairProgress
    data class Done(val result: PairResult) : PairProgress
}

enum class PairResult { PAIRED, PIN_WRONG, ALREADY_IN_PROGRESS, FAILED, CANCELLED }
```

`HostCapabilities` is what the UI uses to enable/disable settings rows. **A settings control is
never silently hidden — it is disabled with an info popover explaining why**, per the UI spec.

### 5.2 `StreamSession` — the streaming side

```kotlin
interface StreamSession {
    val state: StateFlow<SessionState>
    val stats: StateFlow<StreamStats>

    suspend fun start(config: SessionConfig)
    fun stop(quitApp: Boolean)

    /** Attach/detach the rendering surface. Safe to call across surface recreation. */
    fun attachSurface(surface: Surface, width: Int, height: Int)
    fun detachSurface()

    /** Input entry points — all non-blocking, all batched internally. */
    val input: InputSink
}

interface InputSink {
    fun mouseMoveRelative(dx: Int, dy: Int)
    fun mouseMoveAbsolute(x: Int, y: Int, refWidth: Int, refHeight: Int)
    fun mouseButton(button: MouseButton, down: Boolean)
    fun scroll(vertical: Float, horizontal: Float)      // in wheel clicks; 1.0 == 120 units

    fun key(vkCode: Int, down: Boolean, modifiers: Int)
    fun text(utf8: String)

    fun controllerState(slot: Int, state: ControllerState)
    fun controllerArrived(slot: Int, info: ControllerInfo)
    fun controllerRemoved(slot: Int)
    fun controllerMotion(slot: Int, type: MotionType, x: Float, y: Float, z: Float)

    fun touch(event: TouchEventType, pointerId: Long, x: Float, y: Float,
              pressure: Float, majorAxis: Float, minorAxis: Float)
    fun cancelAllTouches()
}
```

```kotlin
data class SessionConfig(
    val host: Host,
    val app: AppEntry,
    val resume: Boolean,
    val settings: StreamSettings,       // already merged: global + per-host override
    val attachedGamepadMask: Int,
    val displayRefreshRateX100: Int,
)

data class StreamStats(
    val fpsReceived: Float,
    val fpsRendered: Float,
    val bitrateMbps: Float,
    val packetLossPercent: Float,
    val decodeTimeMs: Float,
    val hostProcessingLatencyMs: Float?,   // if the host reports it
    val rttMs: Int?,                       // from ENet
    val framesDropped: Long,
    val idrRequests: Long,
)
```

### 5.3 Platform-facing interfaces (declared in `protocol/`, implemented in `platform/`)

```kotlin
interface VideoDecoder {
    /** Called once after RTSP negotiation, before frames arrive. */
    fun configure(format: NegotiatedVideoFormat, surface: Surface)
    /** Non-blocking. Returns false if the decoder has no capacity; caller drops + requests IDR. */
    fun submit(unit: DecodeUnit): Boolean
    fun flush()
    fun release()
    val events: Flow<DecoderEvent>       // FirstFrame, TransientError, FatalError, FormatChanged
}

interface AudioPlayer {
    fun configure(config: OpusConfig)
    fun submit(opusPacket: ByteArray, offset: Int, length: Int)
    fun submitSilence(durationMs: Int)    // packet-loss concealment
    fun release()
}

interface ControlTransport {              // implemented by EnetHost
    fun connect(address: InetSocketAddress, connectData: Int, timeoutMs: Int): Boolean
    fun send(type: Int, payload: ByteArray, channel: Int, reliable: Boolean)
    val inbound: Flow<ControlMessage>
    fun disconnect(lingerMs: Int)
}
```

---

## 6. Settings model and per-host overrides

```kotlin
data class StreamSettings(
    // Video
    val bitrateKbps: Int,                 // 500 .. 500_000
    val width: Int, val height: Int, val fps: Int,
    val codec: CodecPreference,           // AUTO | H264 | HEVC | AV1
    val hdr: Boolean,
    val yuv444: Boolean,
    val frameQueueDepth: Int = 0,         // always 0; exposed for debugging only

    // Audio
    val audioChannels: AudioChannels,     // STEREO | SURROUND_51 | SURROUND_71
    val playAudioOnHost: Boolean,

    // Touch & controller
    val touchMode: TouchMode,             // TOUCHPAD | NATIVE | ABSOLUTE
    val onScreenWidgetsEnabled: Boolean,
    val dividerPositionPercent: Int,      // 0..100, split point for dual-touchpad mode
    val touchPointerVelocityPercent: Int, // 25..400
    val widgetSet: WidgetSet,             // OFF | SIMPLE | FULL | CUSTOM(disabled in v1)
    val swapFaceButtons: Boolean,         // A/B X/Y swap
    val emulatedControllerType: EmulatedPad, // XBOX360 | DS4 | BOTH
    val gyroMode: GyroMode,               // OFF | AUTO | BUILT_IN | CONTROLLER
    val gyroSensitivityPercent: Int,      // 25..400

    // Gestures
    val exitGestureFingers: Int,          // 3 or 4
    val exitSwipeDistanceDp: Int,
    val tapToClick: Boolean,
    val twoFingerRightClick: Boolean,
    val threeFingerMiddleClick: Boolean,

    // Host behaviour
    val optimizeGameSettings: Boolean,    // sops
    val persistGamepadsAfterDisconnect: Boolean,
)
```

**Override semantics** (`ResolveEffectiveSettings`):

* Global defaults live in DataStore (one `StreamSettings`).
* Per-host overrides live in Room as a **sparse map of field name → value**, not a full copy.
  A field with no override inherits the global value live — changing a global setting updates
  every host that has not overridden it.
* The UI shows an "Overridden for this host" chip on any row that has a per-host value, with a
  "Reset to global" action.
* `ResolveEffectiveSettings(hostId)` returns the merged object plus a `Set<String>` of
  overridden field names so the UI can render the chips.
* Settings are then **clamped by host + device capability** before being used
  (`HostCapabilities` ∩ `DecoderCapabilities`). Clamping is visible: if the user asked for HDR
  and it is unavailable, the effective setting is `hdr = false` and the UI says so.

---

## 7. Persistence

| Store | Contents |
|---|---|
| **Room** (`voidlink.db`) | `hosts` (id, name, addresses, mac, ports, appVersion, serverKind, capabilities JSON, lastSeen), `host_settings` (hostId, key, value), `paired_certs` (hostId, serverCertDer BLOB) |
| **DataStore (Proto or Preferences)** | Global `StreamSettings`, UI prefs (last selected host, favorites, sidebar collapsed state) |
| **`filesDir/identity/`** | `client.key` (PKCS#8 DER), `client.crt` (DER), `client.pem` |
| **`cacheDir/boxart/`** | `<hostId>_<appId>.png`, LRU-trimmed at 64 MB |

The client identity is generated lazily on first use, guarded by a mutex so two concurrent
pair attempts cannot race and produce two identities.

---

## 8. Activities and process structure

* **`MainActivity`** — single-activity Compose host for Hosts + AppGrid + Settings.
  `windowSoftInputMode=adjustResize`, no `configChanges` tricks; Compose handles rotation.
* **`StreamActivity`** — separate Activity, launched with the `SessionConfig`.
  * `android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|density|uiMode"`
    — we must **not** be recreated mid-stream.
  * Immersive: `WindowInsetsControllerCompat.hide(systemBars)`, sticky immersive behaviour.
  * `FLAG_KEEP_SCREEN_ON`, `setSustainedPerformanceMode(true)`.
  * Owns the `SurfaceView`; the Compose overlay is drawn in a sibling view above it.
  * Binds `StreamingService` for the foreground-service lifetime.
* **`StreamingService`** — foreground service (`mediaPlayback`) that **owns the
  `StreamSession`**, so a brief Activity teardown does not kill the stream. The Activity binds
  to it and observes its flows.

**Why a separate Activity:** the stream needs different window flags, different orientation
handling, and a surface that must not be recreated by Compose navigation. Mixing it into the
main nav graph invites subtle surface-lifetime bugs.

---

## 9. Error surface and logging

* One `VoidLog` object wrapping `android.util.Log`, with per-subsystem tags
  (`VL.Http`, `VL.Pair`, `VL.Rtsp`, `VL.Enet`, `VL.Video`, `VL.Audio`, `VL.Input`).
* **A ring buffer of the last 2000 log lines is kept in memory** and can be dumped from a
  hidden "Diagnostics" row in settings. This is how we debug the UNVERIFIED items on real
  hardware without a cable.
* **Never log:** the private key, the `riKey`, the PIN after pairing completes, or full packet
  payloads at INFO level. Hex dumps are DEBUG-only and length-capped.
* Every UNVERIFIED code path logs at WARN the first time it executes, once per session.

---

## 10. Testing strategy

| Layer | Test |
|---|---|
| `ByteIO`, every packet builder | JVM unit tests round-tripping against hand-written hex fixtures. **Non-negotiable** — this is where byte-order bugs die. |
| `PairingCrypto` | Known-answer tests: fixed salt + PIN ⇒ fixed AES key; fixed inputs ⇒ fixed hashes. |
| `SdpBuilder` | Golden-file test of the full ANNOUNCE payload for three configurations (1080p60 H.264 stereo; 4K60 HEVC HDR; 1080p120 AV1). |
| `XmlResponse` / `ServerInfo` | Parse fixtures captured from a real Sunshine and a real GFE response, including a malformed/truncated one. |
| `FrameAssembler` | Synthetic packet sequences: in-order, reordered, single loss, block loss, multi-FEC. Asserts the exact bytes of the reassembled frame. |
| `EnetHost` | Loopback test: two instances talking to each other over `127.0.0.1`, asserting reliable ordered delivery under simulated loss. |
| `ResolveEffectiveSettings` | Property test of merge + clamp. |
| UI | Compose screenshot tests of `HostCard` in all four states, and of each settings row type in light and dark. |

**Integration testing requires a real host.** Sunshine on a dev machine is the reference. A
`--fake-host` debug build flavor that replays a captured session from a file is worth building
as soon as we have one capture (see `04-ROADMAP.md` Phase 8).
