# 04 — Implementation Roadmap and Risks

**Ordering principle:** every phase ends with the app **compiling, installable, and running**.
No phase leaves a half-wired subsystem that breaks the build. Where a phase depends on
something not yet built, it depends on an *interface* with a stub implementation, and the stub
is honest (it logs and returns a failure, it does not pretend).

**Second principle:** get to "pixels on screen from a real PC" as early as possible. Everything
after that is refinement against a working baseline. Phases 1–7 are the critical path to a
first frame; 8 onward is quality.

---

## Phase 0 — Skeleton and tokens

*(Runs alongside the build scaffolding another agent is producing.)*

**Build**
* Package tree from `02-ARCHITECTURE.md` §2, with empty files where useful.
* `VoidLinkApp`, `AppContainer`, `MainActivity` with a Compose `setContent`.
* Theme: `Color.kt`, `Type.kt`, `Shape.kt`, `Dimens.kt`, `VoidLinkTheme.kt` implementing
  every token in `03-UI-SPEC.md` §1, both palettes, plus `LocalVoidTokens`.
* `voidClickable()` modifier (no ripple, scale + overlay press states).
* `VoidLog` with subsystem tags and the 2000-line ring buffer.
* `ProtocolConstants.kt` — **every** port, magic, flag, and timeout from `01-PROTOCOL.md`,
  transcribed and cross-referenced by spec section in comments. Nothing else in the codebase
  may define a protocol constant.
* `ByteIO.kt` — explicit LE/BE readers and writers, plus `netfloat` helpers, with unit tests.

**What works end-to-end after this phase:** the app launches to a themed empty screen showing
the design tokens rendered in a gallery preview; `./gradlew test` passes byte-order round-trip
tests.

---

## Phase 1 — Host discovery and the Hosts screen

* `MdnsDiscovery` (NsdManager + MulticastLock + serialized resolve queue).
* `ManualProbe` and the Add Host dialog.
* `NvHttpClient` — **plaintext HTTP only**: `/serverinfo` and its full XML parse
  (`ServerInfo`, `XmlResponse` envelope + `status_code` handling, malformed-document
  detection).
* `HostRepository` + Room `hosts` table + polling with the online/offline timeout split.
* `HostsScreen`, `HostCard` in all states, `EmptyState` variants, pull-to-refresh.
* `WakeOnLan` and the Wake-on-LAN footer action.

**What works end-to-end after this phase:** open the app on the same Wi-Fi as a Sunshine PC and
see a real host card with the real hostname, "Online", and an unpaired padlock badge; add a
host by IP; wake a sleeping PC. Everything else is inert.

---

## Phase 2 — Identity and pairing

* `IdentityStore` + `ClientIdentity`: RSA-2048 keypair, self-signed X.509 (BouncyCastle),
  PEM caching, `uniqueId` generation, all persisted to `filesDir/identity/`.
* `PairingCrypto`: `saltPin`, zero-padded AES-128-ECB block loop, SHA-1/SHA-256 selection,
  with known-answer tests.
* `PinnedTls`: client-cert `KeyManager` + exact-certificate-pinning `TrustManager`.
* `PairingEngine`: all five phases from `01-PROTOCOL.md` §4, including `/unpair` cleanup on
  every failure path and on cancellation.
* `paired_certs` Room table.
* `PairingDialog` with the PIN display, phase progress, and the four distinct outcomes.
* HTTPS `/serverinfo` path and the "genuinely paired" check.

**What works end-to-end after this phase:** tap "Pair with PIN", type the shown PIN into
Sunshine's web UI, and the card flips to paired (padlock badge gone, footer becomes "Connect").
Killing and reopening the app keeps the pairing.

**Gate:** pairing must survive a wrong PIN, a cancelled attempt, and a concurrent attempt from
another device, each producing its correct distinct UI state.

---

## Phase 3 — App list and the app grid

* `/applist` and `/appasset` over pinned HTTPS.
* `BoxArtCache` (disk LRU, 64 MB) and the fallback letter tile.
* `AppGridScreen`, `AppCard`, ordering rules, the running-app ring and "Running" pill.
* `/cancel` wired to the long-press "Quit" action.
* Nav bar with sidebar-toggle and display buttons (the toggle opens an empty settings panel
  for now).

**What works end-to-end after this phase:** tap a paired host, see its real games with real box
art, and quit a running game from the client.

---

## Phase 4 — Settings infrastructure

* `StreamSettings` model, `SettingsRepository` (DataStore global + Room sparse per-host
  overrides), `ResolveEffectiveSettings` with merge + capability clamping.
* All row components: `SettingsRow`, `SliderRow`, `SegmentedRow`, `ToggleRow`, `StepperRow`,
  `NavigationRow`, `InfoButton`, `SectionHeader`.
* `SettingsPanel` with the three window-size-class presentations.
* Every section and row from `03-UI-SPEC.md` §4.8, with real help text written for each.
* Per-host override chips and reset actions; the Favorites mechanism.
* Screenshot tests for each row type, light and dark, enabled and disabled.

**What works end-to-end after this phase:** the complete settings experience is real and
persistent, including per-host overrides — the values simply do not affect a stream yet
because there is no stream.

**Why here, before streaming:** the stream needs a fully-resolved `StreamSettings` to
negotiate, and building settings under time pressure later is how apps end up with a
`SharedPreferences` mess.

---

## Phase 5 — Launch and RTSP negotiation

* `SessionConfig`, `SessionState`, `StreamSession` interface, `StreamSessionImpl` with the
  state machine and all timeouts from `02-ARCHITECTURE.md` §4.2.
* `/launch` and `/resume` with the complete query string, the SOPS clamp, and the NVIDIA
  `fps=0` workaround.
* `RtspClient`: message serialization/parsing (forgiving), OPTIONS, DESCRIBE, SETUP ×3 with
  `Transport`/`Session`/`X-SS-*` header parsing, ANNOUNCE, PLAY ×2.
* `SdpBuilder` with the full attribute set, and `SdpParser` for `surround-params`.
* `StreamActivity` + `LaunchProgress` overlay driven by `SessionState`.
* `StreamingService` foreground service, wake/wifi locks, notification.

**What works end-to-end after this phase:** tapping a game launches it on the PC, the RTSP
handshake completes, PLAY returns 200, and the app sits on a black stream screen showing
"Waiting for first frame…". **The game is actually running on the host.** Nothing is decoded
yet.

**Gate:** golden-file test of the ANNOUNCE payload for three configurations must pass, and a
real host must accept all three.

---

## Phase 6 — Video: receive, reassemble, decode

* `UdpSocketFactory` with 1 MB+ receive buffers and buffer pooling.
* `VideoReceiver` on its dedicated thread + the 500 ms ping thread (legacy and
  `X-SS-Ping-Payload` variants).
* `RtpParser`, `NvVideoPacket`, `FrameAssembler` — **without** Reed-Solomon: complete a frame
  only when all data shards arrive; on any loss, drop the frame and request an IDR.
* `DecoderProbe` (hardware codec enumeration, capability intersection, codec selection ladder).
* `MediaCodecVideoDecoder`: async mode, dedicated `HandlerThread`, low-latency keys with the
  three-tier configure fallback, immediate `releaseOutputBuffer(index, true)`.
* `SurfaceView` sizing/letterboxing and surface attach/detach across lifecycle.
* Bounded decode queue (capacity 2, drop-oldest + IDR).

**Reed-Solomon is deliberately deferred to Phase 10.** On a wired or clean Wi-Fi LAN, loss is
near zero, and shipping the reassembler without FEC gets us a picture weeks earlier while
isolating the riskiest unverified detail in the whole project.

**What works end-to-end after this phase:** **a live game renders on the phone.** No audio, no
input, and any packet loss causes a visible hitch as we re-key.

**Gate:** `FrameAssembler` synthetic-sequence unit tests (in-order, reordered, single loss,
multi-FEC-block) assert exact reassembled bytes.

---

## Phase 7 — Control stream and input

This is the phase most likely to slip. Budget accordingly.

* `EnetHost` / `EnetPeer` / `EnetChannel`: connect handshake with connect data, reliable
  ordered channels, ACKs, retransmit, fragmentation, ping/timeout. Validated by a loopback
  test between two instances with simulated loss and reordering **before** it is pointed at a
  real host.
* `ControlStream`: Start A/B, periodic ping (`0x0200`, reliable, 500 ms), inbound message
  dispatch, termination handling with error-code mapping, IDR requests (rate-limited to one
  per 100 ms).
* `InputCrypto` with the pluggable IV strategy (documented default + counter fallback behind a
  debug setting).
* `InputSender` with batching rules, all-reliable sending, and `InputPackets` builders for
  every packet in `01-PROTOCOL.md` §10.3.
* `KeyCodeMap` (Android → Windows VK).
* `ControllerManager` / `ControllerMapper`: hotplug, axis mapping, dead zones, Y inversion,
  arrival/removal events, `SS_CONTROLLER_ARRIVAL` on Sunshine.
* `TouchpadOverlay` implementing all three touch modes and the exit gesture with its
  buffer-and-replay behaviour.
* `RumbleSink`.

**What works end-to-end after this phase:** **the app is usable.** Move the touchpad and the
host cursor moves; plug in a Bluetooth controller and play the game; the exit gesture
disconnects cleanly and the host session ends properly.

**Gate before declaring the phase done:**
1. Mouse movement visibly moves the host cursor (this validates the IV chaining — see Risk 2).
2. A clean disconnect leaves the host reporting `SUNSHINE_SERVER_FREE`, not a stuck session.
3. No stuck buttons after backgrounding the app mid-input.

---

## Phase 8 — Audio

* `AudioReceiver` + ping thread, RTP parse, payload types 97/127.
* `AudioFecBlock` assembly and in-order dequeue **without** RS recovery; a gap submits silence
  of exactly one packet duration.
* `OpusConfig` construction, `OpusHead` `csd-0`/`csd-1`/`csd-2` synthesis.
* `MediaCodecOpusPlayer` + `AudioTrack` in low-latency mode.
* Stereo only; the 5.1/7.1 segments stay disabled with their explanatory info text.
* A session capture/replay debug flavor (`--fake-host`) that records raw UDP payloads to a
  file and replays them, so Phases 10–11 can be developed without a PC in the loop.

**What works end-to-end after this phase:** game audio plays in sync with video.

---

## Phase 9 — Stream overlay and on-screen controls

* Stats chip, connection-quality warning, toasts.
* In-stream settings drawer with the reduced row set, live-apply vs "Reconnect required".
* `OnScreenControls`: the data-driven `WidgetSpec` layout system, Simple and Full presets,
  floating virtual sticks, analog triggers, auto-hide, auto-hide-on-physical-controller.
* Soft keyboard with stream lifting and the modifier toolbar.
* Disconnect / quit confirmations.

**What works end-to-end after this phase:** the app is pleasant to use on a phone with no
controller: on-screen gamepad, live bitrate changes, stats, keyboard.

---

## Phase 10 — Reed-Solomon FEC

* `ReedSolomon` over GF(2^8) with the systematic matrix, `encode`/`decode(shards, marks)`.
* Wire into `FrameAssembler` and `AudioFecBlock` **behind a setting that defaults to on**, with
  a kill switch.
* Validate against captured real traffic from Phase 8's replay tool: replay a capture with
  shards artificially dropped and assert the recovered frame is byte-identical to the
  undropped reassembly.
* Sunshine per-frame FEC status reporting (`0x5502`).

**What works end-to-end after this phase:** streaming survives real packet loss without
visible hitching; the picture stays clean on a congested Wi-Fi network.

**If the matrix turns out to be incompatible** (see Risk 1), the fallback is the existing
drop-and-IDR path, which is exactly what we shipped in Phase 6 — so this phase can fail
without taking the product down with it.

---

## Phase 11 — HDR, codec breadth, and polish

* HDR: 10-bit profile negotiation, `KEY_HDR_STATIC_INFO`, `COLOR_TRANSFER_ST2084`, display
  capability checks, the mid-stream HDR-mode control message.
* AV1 path validated on devices that have real AV1 hardware decode.
* YUV 4:4:4 where the decoder supports it.
* Frame-pacing measurement and the stats we surface.
* Diagnostics screen (log ring-buffer dump, effective settings, host capabilities).
* Accessibility pass: TalkBack over every screen, 1.3× font scale, RTL.
* Screenshot-test coverage completed.

**What works end-to-end after this phase:** feature-complete v1.

---

## Phase 12 — Deferred / post-v1

Listed so nobody mistakes them for gaps: on-screen control **layout editor** (the "Custom"
segment), surround audio via a bundled Opus decoder, external-display presentation mode,
per-app settings overrides, automatic reconnect, ENet-transported RTSP (`rtspru`), control- and
video-stream encryption, keyboard macro editor.

---

## Risk register

Ordered by expected pain. For each: what breaks, how we know early, and what we do about it.

### 1. Reed-Solomon interoperability — **highest**

*The risk:* GF(2^8) Reed-Solomon has multiple incompatible matrix constructions. If ours
differs from the host's, recovery silently produces corrupt frames — which is *worse* than no
recovery, because corrupt frames look like decoder bugs and will send us hunting in the wrong
place for days.

*Early signal:* Phase 10's capture-replay test fails, or recovered frames decode to garbage
while all-shards-present frames are fine.

*Mitigation:* already structural — FEC recovery is **not on the critical path**. Phase 6 ships
without it. Phase 10 is additive and killable. Never enable recovery without the byte-identical
replay test passing first.

*Honest expectation:* **this may not fully work in v1.** LAN streaming with drop-and-IDR is
acceptable; congested Wi-Fi will be noticeably worse than Moonlight until this lands.

### 2. Input encryption IV chaining — **highest**

*The risk:* the AES-GCM IV derivation and per-message chaining rule (spec 01 §10.1) is the
least-documented part of the protocol, and Java's 12-byte GCM IV requirement forces a
truncation choice that is also unverified. Get it wrong and **the host silently discards every
input packet** — no error, no log, just a game that ignores you.

*Early signal:* Phase 7 gate #1. Move the touchpad; if the cursor does not move but video and
control pings are healthy, this is the cause.

*Mitigation:* the IV strategy is an interface with at least two implementations (chained
ciphertext-tail, and incrementing counter) selectable from a debug setting, plus a hex log of
the first three input packets. Debugging this against Sunshine's own logs (Sunshine logs
decrypt failures) is the fastest route.

*Honest expectation:* costs a day, not a week, **if** the strategy is pluggable from the start.
If it is hardcoded, it costs a week.

### 3. Writing ENet from scratch — **high**

*The risk:* ENet is a real reliable-UDP protocol with sequencing, ACK batching, retransmit
timers, fragmentation, MTU discovery, and a connect handshake. Reimplementing "enough of it" is
a classic underestimate. A subtly wrong retransmit timer produces input lag that only shows up
under loss.

*Early signal:* the loopback test in Phase 7 — if it takes more than two days to make two of
our own instances talk reliably under simulated loss, the real host will be worse.

*Mitigation:* implement the **minimum viable subset**: connect, reliable-ordered on two
channels, fragmentation, disconnect. Skip bandwidth throttling, unsequenced delivery, and
compression entirely. Test on loopback before touching a host. Keep the send path
single-threaded.

*Escape hatch:* if this stalls the project, the licensing-clean option is to bind the upstream
C library via the NDK. That contradicts our "no native code" posture in `00-OVERVIEW.md` §4 and
must be a deliberate, discussed decision — not a quiet Friday-afternoon fix.

### 4. MediaCodec low-latency behaviour across vendors — **high**

*The risk:* the standard `KEY_LOW_LATENCY` is API 30+ and inconsistently honoured; the vendor
keys are undocumented and differ per SoC; some codecs throw on `configure()` when given a key
they dislike; some buffer 3–4 frames regardless of what we ask, which destroys the value
proposition.

*Early signal:* Phase 6 on a real device — measure the delta between "frame fully reassembled"
and "frame rendered". More than ~2 frame intervals means the codec is queueing.

*Mitigation:* the three-tier configure fallback (all keys → standard keys → none), never
letting a vendor key block streaming; per-device logging of which keys were accepted; a
device-quirks table we populate from real logs.

*Honest expectation:* latency will vary meaningfully by device and we will not be able to fix
the worst offenders. Set expectations rather than chasing them.

### 5. GFE (NVIDIA) hosts are largely untestable — **medium**

*The risk:* GameStream is discontinued. We carry Gen 3/4/5 code paths and NVIDIA-specific
workarounds (SOPS clamping, the `fps=0` hack, no Native Touch, no controller-arrival packets)
that we probably cannot test against real hardware.

*Mitigation:* implement them from the spec, gate them behind `isNvidiaGfe`, and **log loudly**
when a GFE path executes. Do not let untestable GFE code complicate the Sunshine path — where
they diverge, branch early and keep the branches separate.

*Honest expectation:* **GFE support is best-effort and may be broken at ship.** Say so in the
release notes rather than implying parity.

### 6. Surround audio — **medium, and already scoped out**

*The risk:* Android's `MediaCodec` Opus decoder does not reliably handle multistream
(mapping family 1) surround. The channel-order fix-up (spec 01 §8.3) adds a second place to be
wrong.

*Mitigation:* v1 is stereo-only with the surround options visibly disabled and explained. The
fix is a bundled libopus, which is a Phase 12 item.

*Honest expectation:* **5.1/7.1 will not work in v1.** This is a deliberate, documented gap,
not a bug.

### 7. mDNS discovery reliability — **medium**

*The risk:* `NsdManager` is genuinely flaky: serialized resolution on older APIs, silent
failures behind VPNs, routers with client isolation, ROMs that need a multicast lock.

*Mitigation:* manual IP entry is a first-class feature, not a fallback; the empty state names
the likely cause; saved hosts are polled directly by IP and do not depend on discovery at all
after the first sighting.

*Honest expectation:* some users will always have to add hosts manually. Design for it.

### 8. Foreground-service and background-execution policy — **medium**

*The risk:* API 34 tightens foreground-service types; OEM battery managers kill long-running
services aggressively; a killed service mid-stream leaves a stuck session on the host.

*Mitigation:* correct `mediaPlayback` type + runtime permission; the service owns the session
so Activity churn is harmless; the host's own session timeout is the backstop. Test on at
least one Xiaomi/Samsung device, where the policies are harshest.

### 9. HDR correctness — **medium-low**

*The risk:* HDR that "works" but looks washed out or over-saturated is common and hard to
diagnose. Metadata plumbing (`KEY_HDR_STATIC_INFO`, transfer function, the mid-stream HDR
control message whose layout is unverified) has several failure points that all look the same
on screen.

*Mitigation:* Phase 11, after everything else is stable. Log the codec's actual output format.
Ship with HDR **off by default**.

### 10. Codec/host capability negotiation guesswork — **low-medium**

*The risk:* `ServerCodecModeSupport`'s bit assignments are unverified (spec 01 §3.3.1); we may
offer a codec the host cannot encode.

*Mitigation:* the failure mode is clean — ANNOUNCE returns a non-200 and we surface it.
Use the reliable `MaxLumaPixelsHEVC == 0` check for HEVC, treat the bitfield as a hint, and log
the raw value from every host so the table can be corrected from real data.

### 11. Scope creep in the on-screen controls — **low, but insidious**

*The risk:* the reference's on-screen widget system is enormous (custom layouts, profiles,
duplicate/delete, visual feedback). It is easy to spend the whole project there.

*Mitigation:* v1 ships **two fixed presets** built on a data-driven layout model. "Custom" is
visibly disabled with an honest explanation. The editor is Phase 12 and the data model already
supports it, so deferring costs nothing structurally.

### 12. Byte-order and struct-packing bugs — **certain, but cheap if caught early**

*The risk:* not a matter of *if*. The protocol mixes LE and BE within a single packet
(spec 01 §0.1), and a wrong-endian field usually manifests as a completely dead subsystem
rather than a subtle glitch.

*Mitigation:* the non-negotiable rule from `02-ARCHITECTURE.md` §10 — every packet builder gets
a hex-fixture round-trip unit test written **at the same time as** the builder. This is the
single highest-value testing investment in the project.

---

## Summary of what is honestly likely to be incomplete at v1

1. **Reed-Solomon FEC recovery** — may be absent or disabled; degrades quality under loss.
2. **Surround audio (5.1 / 7.1)** — deliberately not shipped; stereo only.
3. **NVIDIA GFE hosts** — implemented from spec, likely untested, possibly broken.
4. **Custom on-screen control layouts** — presets only.
5. **External display / presentation mode** — not implemented.
6. **Automatic reconnect after a drop** — manual resume only.
7. **HDR** — implemented but off by default and expected to need device-specific fixes.
8. **AV1** — negotiated where probed, but hardware decode quality varies wildly.
9. **Encrypted control/video/audio streams (Sunshine)** — negotiated as off.
10. **ENet edge cases** — fragmentation and heavy-loss behaviour will be the least-exercised
    code in the app.
