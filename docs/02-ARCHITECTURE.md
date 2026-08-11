# 02 — Architecture

Single Gradle module `:app`, application id and root package `com.voidlink.android`.
Kotlin only. Compose UI. No dependency injection framework — a hand-written
`ServiceLocator` object (`di/ServiceLocator.kt`) is enough and keeps build times down.

> **This document describes shipped code.** §2, §6 and §7 were reconciled against the tree that
> actually exists (`find app/src -name '*.kt'`) and against `gradle/libs.versions.toml`, which is
> **fixed** — no dependency may be added to it. Sections describing not-yet-written packages say
> so explicitly.

---

## 1. Layer model

```
┌──────────────────────────────────────────────────────────────────────┐
│  ui/            Compose screens, components, theme, viewmodels       │
│                 Knows about: data models, StreamSession interface     │
│                 Knows NOTHING about: sockets, XML, byte layouts       │
├──────────────────────────────────────────────────────────────────────┤
│  data/          Models, repositories, persistence (DataStore + JSON), │
│                 and the stubbed provider interfaces the protocol      │
│                 layer will implement. No separate domain/ layer.      │
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

### 2.1 What exists today (shipped, verified against the tree)

```
com.voidlink.android
│
├── VoidLinkApplication.kt          Application; calls ServiceLocator.initialize()
│
├── di/
│   └── ServiceLocator.kt           The whole dependency graph: two repositories +
│                                   three swappable provider singletons
│
├── data/
│   ├── StreamSettings.kt           THE settings model + every settings enum
│   │                               (VideoCodec, StreamResolution, FrameRate, TouchMode,
│   │                                OnScreenWidgetPreset, EmulatedControllerType, GyroMode,
│   │                                GestureAction, ExternalDisplayMode, SurroundMode)
│   ├── SettingsRepository.kt       Global settings, DataStore-Preferences + JSON blob
│   ├── SettingsFormat.kt           Value formatters shared by rows ("23.0 Mbps", "100%")
│   ├── KnownHost.kt                KnownHost, HostStatus, HostReachability,
│   │                               DiscoveredHost, HostStatusProvider (+ stub)
│   ├── HostRepository.kt           Known-host CRUD, DataStore-Preferences + JSON list
│   ├── HostWaker.kt                HostWaker interface (+ stub)
│   └── AppCatalog.kt               HostApp, AppCatalogProvider (+ stub)
│
└── ui/
    ├── MainActivity.kt
    ├── navigation/
    │   ├── VoidLinkApp.kt          ROOT COMPOSABLE (not the Application class — see below)
    │   └── VoidLinkNavigation.kt   NavHost, routes, arguments
    ├── theme/
    │   ├── Color.kt                Token values, light + dark palettes
    │   ├── Type.kt                 Type scale
    │   ├── Spacing.kt              Spacing AND corner-radius AND elevation tokens
    │   └── Theme.kt                MaterialTheme wrapper + LocalVoidTokens
    ├── components/
    │   ├── Primitives.kt           HairlineDivider, VoidLinkCard, GlyphTile,
    │   │                           StatusLine, ScreenHeader
    │   ├── SettingsRows.kt         SettingsSection, SliderRow, SegmentedRow,
    │   │                           SegmentedControl, ToggleRow, PickerRow,
    │   │                           InfoButton, InfoToggleGlyph, InlineInfoText
    │   └── VoidLinkIcons.kt        Named icon inventory (see 03-UI-SPEC §1.8)
    ├── hosts/
    │   ├── HostsScreen.kt          Includes the host card and add-host dialog
    │   └── HostsViewModel.kt
    ├── apps/
    │   ├── AppsScreen.kt           (NOT "AppGridScreen")
    │   └── AppsViewModel.kt
    ├── settings/
    │   ├── SettingsScaffold.kt     Size-class presentation: split / drawer / full-screen
    │   ├── SettingsSidebar.kt      The panel and all five sections
    │   └── SettingsViewModel.kt
    └── stream/
        └── StreamActivity.kt       Shell; owns the surface and orientation policy
```

**Two names, one word — do not confuse them:**

| Name | What it is |
|---|---|
| `com.voidlink.android.VoidLinkApplication` | The `android.app.Application` subclass |
| `com.voidlink.android.ui.navigation.VoidLinkApp` | The **root `@Composable`** that hosts the nav graph |

Deviations from the original plan that are now normative: there is **no `domain/` layer**
(models and the settings/host contracts live in `data/`), **no `AppContainer`** (it is
`di/ServiceLocator.kt`), **no separate `Shape.kt`/`Dimens.kt`** (both fold into
`theme/Spacing.kt`), and settings sections are functions inside `SettingsSidebar.kt` rather
than a `sections/` package.

### 2.2 What the protocol layer adds (not yet written)

These packages do not exist yet. The protocol coder creates them; nothing above changes.

```
com.voidlink.android
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
│   │   ├── ReedSolomon.kt          (Phase 11; interface + no-op impl first)
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

### 2.3 How the protocol layer attaches

The UI is already wired to three interfaces in `data/`, each currently satisfied by an
honest stub that reports "nothing found / offline / not implemented":

| Interface (in `data/`) | Stub today | Real implementation lands in |
|---|---|---|
| `HostStatusProvider` (`probe`, `discover`) | `StubHostStatusProvider` | `platform/discovery/` + `protocol/http/` |
| `AppCatalogProvider` | `StubAppCatalogProvider` | `protocol/http/` |
| `HostWaker` | `StubHostWaker` | `platform/net/WakeOnLan.kt` |

Turning the protocol on is **three assignments** in `ServiceLocator` at app start-up. No UI
code changes, no repository changes. That property is worth preserving: any new protocol
capability the UI needs should arrive as another `data/`-declared interface with a stub, not as
a direct dependency from `ui/` onto `protocol/`.

**The `protocol/` purity rule still holds and is about to be tested:** no `androidx.*` import,
no Compose reference, no `android.*` outside the thin bindings that live in `platform/`.
Everything in `protocol/` must be JVM-unit-testable without an emulator — which, in this
environment, is the *only* kind of test that can run at all (see `04-ROADMAP.md` §Verification).

---

## 3. Threading model

Latency is the product. Thread discipline is how we get it.

| Thread / dispatcher | Owner | Work | Never does |
|---|---|---|---|
| **Main** | Android | Compose recomposition, Activity lifecycle, `SurfaceHolder` callbacks | Any network, crypto, or file I/O |
| **`Dispatchers.IO`** | coroutines | NVHTTP calls, pairing, RTSP handshake, box-art fetches, DataStore reads/writes | Nothing in the per-frame hot path |
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
    data class QueryingServer(val host: KnownHost) : SessionState
    data class Pairing(val pin: String, val phase: PairPhase) : SessionState
    data class LoadingApps(val host: KnownHost) : SessionState
    data class AppsReady(val host: KnownHost, val apps: List<HostApp>, val runningAppId: String?) : SessionState
    data class Launching(val app: HostApp, val progress: LaunchStep) : SessionState
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

### 5.1 The launcher side — persistence and liveness are separate

This is the shipped shape, and it is deliberately split in two: `HostRepository` is **pure
storage and never touches a socket**, while liveness comes from a `HostStatusProvider` that the
protocol layer supplies. The view model joins them, which is why the host list survives a dead
network instead of emptying itself.

```kotlin
// data/HostRepository.kt — a concrete class, not an interface. No networking.
class HostRepository(dataStore: DataStore<Preferences>) {
    val hosts: Flow<List<KnownHost>>                 // sorted by lowercased name
    suspend fun snapshot(): List<KnownHost>
    suspend fun upsert(host: KnownHost)
    suspend fun updateHost(uuid: String, transform: (KnownHost) -> KnownHost): Boolean
    suspend fun delete(uuid: String)
    suspend fun rename(uuid: String, newName: String)
    suspend fun markPaired(uuid: String)
    suspend fun markUnpaired(uuid: String)
    suspend fun setSettingsOverride(uuid: String, override: StreamSettings?)
    suspend fun mergeDiscovered(discovered: DiscoveredHost, nowEpochMillis: Long)
    suspend fun addManualHost(address: String, name: String? = null): KnownHost
}

// data/KnownHost.kt — what is persisted
@Serializable
data class KnownHost(
    val uuid: String,                     // host's advertised id, or a local UUID if manual
    val name: String,
    val addresses: List<String> = emptyList(),   // most recently successful first
    val macAddress: String? = null,
    val paired: Boolean = false,
    val settingsOverride: StreamSettings? = null,
    val lastSeenEpochMillis: Long = 0L,
    val manuallyAdded: Boolean = false,
) {
    val primaryAddress: String?
    val canWakeOnLan: Boolean             // == macAddress is non-blank
    fun effectiveSettings(globalSettings: StreamSettings): StreamSettings
    fun withPreferredAddress(address: String): KnownHost
    fun markSeen(nowEpochMillis: Long, atAddress: String? = null): KnownHost
}

// data/KnownHost.kt — transient liveness, never persisted
data class HostStatus(
    val reachability: HostReachability = UNKNOWN,   // UNKNOWN | ONLINE | OFFLINE
    val paired: Boolean = false,
    val runningAppId: String? = null,
    val runningAppName: String? = null,
    val hostName: String? = null,                   // keeps a renamed PC in sync
) { val isOnline: Boolean }

// The seam the protocol layer fills (stubbed today)
interface HostStatusProvider {
    suspend fun probe(host: KnownHost): HostStatus  // never throws on ordinary failure
    fun discover(): Flow<DiscoveredHost>            // cold: collect starts, cancel stops
}

interface AppCatalogProvider { /* host's app list */ }
interface HostWaker { /* Wake-on-LAN */ }
```

Notes that matter for the protocol coder:

* **`runningAppId` is a `String`**, not a numeric type — `/applist` ids exceed `Int` range
  (spec 01 §3.4) and the UI only ever compares and displays them.
* `KnownHost` holds **no ports, no `appVersion`, no capability flags.** Those are session-time
  concerns; if the UI later needs them for enable/disable decisions, they belong on
  `HostStatus` (transient) — **not** on `KnownHost`, which would make them stale storage.
* Pairing is not on `HostRepository`. It belongs on a new `data/`-declared interface
  (`PairingController`, emitting `PairProgress` below) with the same stub-then-swap pattern.

```kotlin
sealed interface PairProgress {
    data class PinReady(val pin: String) : PairProgress   // show this to the user NOW
    data class Phase(val n: Int) : PairProgress
    data class Done(val result: PairResult) : PairProgress
}

enum class PairResult { PAIRED, PIN_WRONG, ALREADY_IN_PROGRESS, FAILED, CANCELLED }
```

**Capability-driven disabling:** where the UI must disable a control because a host cannot do
the thing (Native Touch on GFE, YUV 4:4:4 anywhere but Sunshine), the flag arrives on
`HostStatus`. **A settings control is never silently hidden — it is disabled with its info text
explaining why**, per the UI spec.

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
    val host: KnownHost,
    val app: HostApp,                   // data/AppCatalog.kt
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

**`data/StreamSettings.kt` is the source of truth.** The canonical field list below is
transcribed from the shipped file; `03-UI-SPEC.md` §4.8 renders exactly these fields and no
others. Adding a UI row without adding a field here — or vice versa — is a defect.

```kotlin
@Serializable
data class StreamSettings(
    // ---- Video -------------------------------------------------------------
    val bitrateKbps: Int = 20_000,                    // 500 .. 150_000, coerced
    val codec: VideoCodec = AUTO,                     // H264 | HEVC | AV1 | AUTO
    val hdrEnabled: Boolean = false,
    val yuv444Enabled: Boolean = false,
    val resolution: StreamResolution = RES_1080P,     // 720p|1080p|1440p|4K|Native
    val frameRate: FrameRate = FPS_60,                // 30|60|90|120
    val optimizeGameSettings: Boolean = true,         // the host's `sops` flag
    val showStatsOverlay: Boolean = false,

    // ---- Touch & Controller ------------------------------------------------
    val touchMode: TouchMode = NATIVE_TOUCH,          // TOUCHPAD|NATIVE_TOUCH|ABSOLUTE_TOUCH
    val onScreenWidgetEnabled: Boolean = true,
    val dividerPositionPercent: Int = 50,             // 10..90, coerced
    val touchPointerVelocityPercent: Int = 100,       // 25..300, coerced
    val onScreenWidgets: OnScreenWidgetPreset = SIMPLE, // OFF|SIMPLE|FULL|CUSTOM
    val swapFaceButtons: Boolean = false,
    val emulatedControllerType: EmulatedControllerType = XBOX_360, // XBOX_360|DUALSHOCK_4|BOTH
    val gyroMode: GyroMode = OFF,                     // OFF|AUTO|BUILT_IN|CONTROLLER
    val gyroSensitivityPercent: Int = 100,            // 25..300, coerced
    val rumbleEnabled: Boolean = true,

    // ---- Gestures ----------------------------------------------------------
    val threeFingerTapEnabled: Boolean = true,
    val threeFingerTapAction: GestureAction = TOGGLE_KEYBOARD,
    val edgeSwipeEnabled: Boolean = true,
    val edgeSwipeAction: GestureAction = TOGGLE_SETTINGS,
    //   GestureAction = NONE | TOGGLE_KEYBOARD | TOGGLE_SETTINGS | TOGGLE_OVERLAY | DISCONNECT

    // ---- Peripherals -------------------------------------------------------
    val externalDisplayMode: ExternalDisplayMode = MIRROR,  // MIRROR | SEPARATE
    val captureMouse: Boolean = true,
    val forwardKeyboard: Boolean = true,

    // ---- Audio -------------------------------------------------------------
    val surroundMode: SurroundMode = STEREO,          // STEREO | SURROUND_5_1 | SURROUND_7_1
    val muteHostAudio: Boolean = false,
)
```

Numeric ranges are enforced by `coerced()`, which the repository applies on **both** read and
write, so a hand-edited or downgraded blob can never produce an out-of-range value.

### 6.1 How each field reaches the wire

Every field must map to something in `01-PROTOCOL.md` or be explicitly local-only. There is no
third category.

| Field | Protocol destination |
|---|---|
| `bitrateKbps` | SDP `x-nv-video[0].initialBitrateKbps` / `bw.minimumBitrateKbps` / `bw.maximumBitrateKbps` (§6.4). **Fixed at ANNOUNCE; cannot change mid-session.** |
| `codec` | SDP `x-nv-vqos[0].bitStreamFormat` + `x-nv-clientSupportHevc` (§6.4/§7.2) |
| `hdrEnabled` | `/launch?hdrMode=1&clientHdrCap*` (§3.6) + SDP `dynamicRangeMode` |
| `yuv444Enabled` | SDP `x-ss-video[0].chromaSamplingType` (Sunshine only) |
| `resolution`, `frameRate` | `/launch?mode=WxHxF` + SDP `clientViewportWd/Ht`, `maxFPS` |
| `optimizeGameSettings` | `/launch?sops=` — **but forced to `0`** by the NVIDIA non-standard-resolution clamp (§3.6) regardless of the user's choice |
| `showStatsOverlay` | **Local only** — draws the overlay chip |
| `touchMode` | Selects between `SS_TOUCH` packets, relative-mouse packets, and absolute-mouse packets (§10.3) |
| `dividerPositionPercent`, `touchPointerVelocityPercent` | **Local only** — they shape how touches become packets, and are never transmitted |
| `onScreenWidgetEnabled`, `onScreenWidgets`, `swapFaceButtons` | **Local only** — they synthesize ordinary controller packets |
| `emulatedControllerType` | `SS_CONTROLLER_ARRIVAL.type` (§6.2 below) |
| `gyroMode`, `gyroSensitivityPercent` | Gates and scales `SS_CONTROLLER_MOTION` (§10.3) |
| `rumbleEnabled` | **Local only** — gates whether an inbound rumble message (§9.6) reaches a motor |
| `threeFingerTap*`, `edgeSwipe*` | **Local only** — gesture recognition and overlay actions |
| `externalDisplayMode` | **Local only**, and inert in v1 (non-goal, `00-OVERVIEW` §4.6) |
| `captureMouse` | **Local only** — whether we request pointer capture |
| `forwardKeyboard` | Gates keyboard/UTF-8 packets (§10.3) |
| `surroundMode` | `/launch?surroundAudioInfo=` + SDP `x-nv-audio.surround.*` (§8.2) |
| `muteHostAudio` | `/launch?localAudioPlayMode=` — **inverted**: `muteHostAudio == true` ⇒ `localAudioPlayMode=0` |

**Launch parameters with no settings field** are derived constants, not user-facing. The
protocol layer must not invent rows for them:

| Parameter | Value |
|---|---|
| `gcpersist` | `1` |
| `remoteControllersBitmap` / `gcmap` | Computed live from connected controllers |
| `rikey` / `rikeyid` | Generated per session |

### 6.2 `EmulatedControllerType.BOTH` — resolved

`SS_CONTROLLER_ARRIVAL` (§10.3) carries **one `uint8 type` per pad**, so "both types at once"
is not representable for a single pad — but it *is* representable across pads. The setting is
therefore defined as a **policy for choosing each pad's advertised type**:

| Setting | `SS_CONTROLLER_ARRIVAL.type` sent for every pad |
|---|---|
| `XBOX_360` | `LI_CTYPE_XBOX` (`0x01`) for all pads, regardless of the physical device |
| `DUALSHOCK_4` | `LI_CTYPE_PS` (`0x02`) for all pads |
| `BOTH` | **Per-pad passthrough:** report each pad's *detected* type — `LI_CTYPE_PS` for a DualShock/DualSense, `LI_CTYPE_NINTENDO` for a Switch pad, `LI_CTYPE_XBOX` otherwise, `LI_CTYPE_UNKNOWN` when undetectable |

`BOTH` is thus "don't force a type", which is what a user with mixed controllers wants. On GFE
(no arrival packet at all) the setting has no effect; the row is disabled with that
explanation.

**Naming:** the UI label stays **"DS4"** (short enough for a segment), the enum constant stays
`DUALSHOCK_4`, and the protocol constant is `LI_CTYPE_PS`. All three name the same thing; the
mapping table above is the single place that says so.

### 6.3 Override semantics

The shipped model is **whole-object override**, not per-field:

* Global settings: one JSON blob in `SettingsRepository`.
* Per-host: `KnownHost.settingsOverride: StreamSettings?` — `null` inherits, non-null wins
  **entirely**. `KnownHost.effectiveSettings(global)` performs the resolution.
* Consequence, and it is a real one: **once a host has an override, later changes to global
  settings no longer reach it.** The UI must say this plainly rather than implying live
  inheritance — see `03-UI-SPEC.md` §4.9.
* Creating an override starts from a copy of the current effective settings, so it never
  arrives half-populated. "Reset to global" sets `settingsOverride = null`.
* Settings are then **clamped by host + device capability** at launch time
  (`HostCapabilities` ∩ decoder capabilities). Clamping is visible: if the user asked for HDR
  and it is unavailable, the effective value is `hdrEnabled = false` and the UI says so.

---

## 7. Persistence

**There is no database. Room and KSP are not in `gradle/libs.versions.toml` and will not be
added.** Everything persists as JSON in DataStore-Preferences, or as files.

| Store | Backing | Contents |
|---|---|---|
| `SettingsRepository` | DataStore-Preferences file `voidlink_settings`, single key `stream_settings_json` | The global `StreamSettings`, one JSON object |
| `HostRepository` | DataStore-Preferences file `voidlink_hosts`, single key `known_hosts_json` | `List<KnownHost>` as one JSON array, kept sorted by lowercased name |
| **`filesDir/identity/`** | Files | `client.key` (PKCS#8 DER), `client.crt` (DER), `client.pem` |
| **`cacheDir/boxart/`** | Files | `<hostUuid>_<appId>.png`, LRU-trimmed at 64 MB |

**Why one blob per store rather than a key per field:** writes are atomic, so a settings change
is never observed half-applied, and schema evolution is free — `ignoreUnknownKeys = true` lets
an older build read a newer blob, and a missing key falls back to the constructor default. Both
repositories decode defensively: a corrupt or unparseable blob degrades to defaults/empty
rather than crashing.

**Paired server certificates** (spec 01 §4.3) have nowhere to live yet. The decision, to keep
the no-database rule intact: add a nullable `serverCertPem: String?` to `KnownHost`. It is a
`@Serializable` data class with defaulted fields, so this is a backward-compatible additive
change requiring no migration — old blobs decode with `null`. The protocol coder makes this
edit when implementing pairing. **Do not introduce a second persistence mechanism for it.**

The client identity is generated lazily on first use, guarded by a mutex so two concurrent
pair attempts cannot race and produce two identities. It stays in `filesDir`, not DataStore —
key material does not belong in a preferences blob.

---

## 8. Activities and process structure

* **`MainActivity`** — single-activity Compose host for Hosts + Apps + Settings, hosting the
  `VoidLinkApp` root composable and the `VoidLinkNavigation` graph.
  `windowSoftInputMode=adjustResize`, no `configChanges` tricks; Compose handles rotation.
* **`StreamActivity`** — separate Activity, launched with the `SessionConfig`.
  * `android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|density|uiMode"`
    — we must **not** be recreated mid-stream.
  * `android:screenOrientation="fullUser"` — **portrait streaming is supported**; the user's
    rotation lock is respected. See `03-UI-SPEC.md` §5.6 for what rotation does and does not
    change.
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
| `XmlResponse` / `ServerInfo` | Parse **hand-written** fixtures modelled on the documented response shapes (spec 01 §3.3), including a malformed/truncated one. Captures from real hosts replace them when someone has one. |
| `FrameAssembler` | Synthetic packet sequences: in-order, reordered, single loss, block loss, multi-FEC. Asserts the exact bytes of the reassembled frame. |
| `EnetHost` | Loopback test: two instances talking to each other over `127.0.0.1`, asserting reliable ordered delivery under simulated loss. |
| `KnownHost.effectiveSettings` / `coerced()` | Merge and clamp behaviour. Already covered by `StreamSettingsTest` and `KnownHostTest`. |
| `SettingsFormat` | Value-string formatting. Already covered by `SettingsFormatTest`. |

**Everything above is a JVM unit test** (`app/src/test/`), which is the only test kind this
environment can execute — there is no emulator and no connected device, so
`androidTest`/Compose screenshot tests cannot run here. Compose `@Preview` functions are still
required on every component for human review, but they are **not** a verification mechanism.

**Integration testing requires a real host**, which we do not have. See `04-ROADMAP.md`
§Verification for the CI-verifiable vs user-verifiable split that follows from this, and for
why no phase gate may depend on a live PC.
