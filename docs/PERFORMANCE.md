# Streaming latency: VoidLink vs. Artemis

Research note. Read-only investigation, August 2026. No code in this repo was changed to produce it.

Scope of the question: users report that **VoidLink** (iOS, `The-Fried-Fish/VoidLink-previously-moonlight-zwm`) feels lower-latency and smoother than Moonlight and than **Artemis** (moonlight-android fork, which this repo is). Is that true, why, and what is portable?

Sources read: VoidLink `Integration` branch at `58535c9`, the `@Acaki_frame_pacing_metal` branch at `0a2fdbf`, upstream `moonlight-stream/moonlight-ios` `master`, and this repository at `1792447` (branch `claude/voidlink-android-streaming-5l52vu`, descended from `moonlight-noir` at `3397ec7`).

---

## 1. Verdict on the claim

**Not substantiated as stated. Partly true in a way that inverts the claim.**

Three findings, in order of how much they matter:

**a) VoidLink's own UI tells users that its default mode is the *higher*-latency one.** VoidLink's frame pacing setting has four modes (`VoidLink/Database/DataManager.h:59-63`):

```objc
typedef NS_ENUM(NSInteger, FramePacingMode) {
    FramePacingModeOff,
    FramePacingModeLegacy,
    FramePacingModeQueue,
    FramePacingModeInterpolation
};
```

Its own localized help strings (`VoidLink/Localization/Localizable.xcstrings`) read:

- `framePacingStackTip`: *"Off = no frame pacing, previously **"Lowest Latency"**. Legacy, previously "Smoothest Video" (though it's not the smoothest option anymore)"*
- `legacyFramePacingTip`: *"Legacy Frame Pacing and No Frame Pacing modes **may reduce streaming performance or cause frequent jitter and stuttering** … Tap [Cancel] to **return to Queue Buffering mode**."*

So the mode VoidLink steers users toward — Queue Buffering — is explicitly a buffering mode. It is smoother *because* it holds frames. It cannot be lower latency than the mode it replaced; it is 1–5 decoded frames of added delay by construction.

**b) VoidLink's "Legacy" mode is byte-for-byte upstream Moonlight iOS.** Compare `VoidLink/Stream/VideoDecoderRenderer.m:618` (comment in the file itself says *"matches upstream/Integration behavior exactly"*) with upstream `moonlight-ios/Limelight/Stream/VideoDecoderRenderer.m`. Both drain `LiPollNextVideoFrame` and break when `LiGetPendingVideoFrames() == 1`, with the same "keep one pending frame to smooth out gaps due to network jitter at the cost of 1 frame of latency" comment. Upstream Moonlight iOS already had a *Lowest Latency* setting that turns that off. **VoidLink added no new lowest-latency path that Moonlight iOS did not already have.** Its Core Data default is `framePacingMode = 1` = Legacy = upstream (`VoidLink/Limelight.xcdatamodeld/VoidLink v1.0.xcdatamodel/contents:64`).

**c) The VoidLink README makes no latency claim at all.** It is entirely about the App Store listing, the UI redesign, and contributor credits. The "lower latency" framing is user/forum perception, not a project claim. Treat it as such.

What *is* real and defensible: VoidLink is **smoother**, and on iOS a smoother stream is very commonly reported as "lower latency" because judder reads as lag. It also has a genuinely better-instrumented perf overlay (ImGui plots of frame time, queue depth, drop rate) which makes it *feel* like a more tuned app.

---

## 2. Platform vs. software: how much of this is iOS being iOS

Most of it. Enumerated so it can be argued with:

| Factor | iOS / VoidLink | Android / Artemis | Portable? |
|---|---|---|---|
| Decoder | One VideoToolbox implementation, one vendor, `VTDecompressionSessionDecodeFrameWithOutputHandler` with a per-frame callback. Decode-to-callback is deterministic. | `MediaCodec`, dozens of vendor implementations, output buffers surfaced via a polled `dequeueOutputBuffer`. Vendor-specific reorder/DPB behaviour has to be beaten out with vendor keys. | No |
| Presentation | `AVSampleBufferDisplayLayer` / Metal with `CAMetalDisplayLink`. Presentation timestamps are honoured by a compositor designed around them. | `Surface` + `releaseOutputBuffer(idx, nanos)` into SurfaceFlinger. Honoured, but with an extra composition hop and variable vendor buffer counts. | No |
| Display | ProMotion 10–120 Hz adaptive, hardware LTPO. `optimizeRefreshRate` (`VideoDecoderRenderer.m:1585`) picks from a fixed, documented supported-rate list per device family. | `Surface.setFrameRate` requests a mode; the compositor may or may not honour it; the supported-rate list is per-device and often lies. AYN Thor is a fixed 120 Hz AMOLED, so this is less of a problem here than on most Android. | Partly (already done) |
| Touch input | 120 Hz touch scan on ProMotion devices, sub-frame event delivery. | Varies wildly; typically 120–240 Hz on gaming handhelds but with more input-stack buffering. | No |
| Thermals / scheduling | `QOS_CLASS_USER_INTERACTIVE` dispatch queues, aggressive turbo. | `THREAD_PRIORITY_URGENT_DISPLAY`, but subject to vendor schedulers and thermal throttling. Artemis already sets the right priorities. | Already done |
| Frame interpolation | `VTFrameProcessor` (iOS 26), `VoidLink/Stream/FrameInterpolator.swift`. | No AOSP equivalent. Would need a hand-written optical-flow shader. And it costs ≥1 frame of latency by definition. | No |

The honest split: of the perceived gap, my estimate is **roughly 70% platform, 20% "VoidLink chose smoothness and it reads as better", 10% real software defects on the Artemis side**. That last 10% is worth fixing, and it is larger in this repo than in stock Artemis — see §4.

One thing that is *not* platform: VoidLink has exactly **one** frame-pacing policy active at a time, selected by a setting, and each policy is internally coherent. Artemis as it exists in this repo runs **two policies simultaneously and non-deterministically**. That is a real software difference and it is fixable.

---

## 3. What VoidLink actually changed (cited)

Everything below is from the `Integration` branch (`58535c9`) unless noted.

### 3.1 Queue-based frame pacing (the flagship change) — *adds latency*

`VoidLink/Utility/FrameQueue.m` is a singleton ring buffer of **decoded** frames with a user-settable high-water mark.

- `_highWaterMark = 2` default in `_initSingleton`; slider range 0–5 (`SettingsViewController.m:2384-2385`); Core Data default `frameQueueSize = 1`.
- `_unsafeEnqueue:withDropTarget:` implements an alternating drop policy: when full, drop the *newest* frame; next time, drop the *oldest* and enqueue. IDR frames always bypass the cap.
- `_ptsCorrection` accumulates dropped-frame durations so the presentation clock does not drift.
- `renderModeAVSB:` (`VideoDecoderRenderer.m:539`) is the `CADisplayLink` callback: it waits for `link.targetTimestamp`, dequeues one frame, and presents it with `CMSampleBufferSetOutputPresentationTimeStamp(frame.sampleBuffer, targetTime)`.
- Gate: `if (_needRequeuing ? _frameQueue.count > MAX(_queueSize-1, 0) : true)` — it deliberately **refuses to present until the queue has filled to depth**, i.e. it prebuffers on stream start and after every stall.
- The comment in the file credits the design: *"inspired by the behavior of moonlight-qt's Pacer class"*.

This is the same idea as moonlight-qt's Pacer. It is a smoothness feature. At 60 fps a depth of 2 is ~33 ms of added display latency; depth 5 is ~83 ms.

Capability flag follows the mode (`VoidLink/Stream/Connection.m:706-717`): Queue/Interpolation use `CAPABILITY_DIRECT_SUBMIT`, Legacy/Off use `CAPABILITY_PULL_RENDERER`.

### 3.2 `asyncFrameDequeue` — a genuine, small latency knob

`VideoDecoderRenderer.m` chooses between `dequeueWithTimeout:completion:` (async, off the display-link thread) and `dequeueWithTimeoutSync:`. VoidLink's own tip string is refreshingly honest:

> `asyncFrameDequeueStackTip`: *"- Disabled: may improve visual smoothness — Enabled: may improve control input responsiveness"*

Even VoidLink frames this as a trade, not a win.

### 3.3 Metal rendering backend + `CAMetalDisplayLink`

Branch `@Acaki_frame_pacing_metal`, merged into Integration. Files: `VoidLink/Metal/MetalVideoRenderer.m`, `MetalView.m`, `MetalViewController.m`. Relevant commits:

- `b6c029d` "feat: bring back CAMetalDisplayLink"
- `ebf4604` "feat: use CAPABILITY_PULL_RENDERER for legacy pacing mode"
- `0a2fdbf` "refactor: separate metrics calculation for legacy and queue pacing"
- `59b1dcf` "feat: add back legacy pacing" (adds the mode selector UI)

The Metal path exists to render EDR/HDR metadata and the ImGui overlay in one pass, and to sidestep `AVSampleBufferDisplayLayer` freezes (`958b391`, `678c805`). It is not a latency change — the Metal path *requires* queue pacing (`SettingsViewController.m:2695` forces `FramePacingModeQueue` and disables the selector).

### 3.4 Frame interpolation (iOS 26 `VTFrameProcessor`)

`VoidLink/Stream/FrameInterpolator.swift`. Forces `_queueSize = 8` when enabled (`VideoDecoderRenderer.m:216`) and runs the display link at 2× stream rate. This *increases* latency substantially — interpolation needs frame N+1 before it can emit the tween between N and N+1 — in exchange for apparent 120 fps. It is a smoothness feature marketed hard. Not portable, and not desirable if latency is the goal.

### 3.5 `optimizeRefreshRate`

`VideoDecoderRenderer.m:1585`. Picks the nearest ProMotion-supported rate ≥ measured stream fps from a hardcoded per-device-class list and sets `preferredFrameRateRange`. Artemis has the equivalent (`Game.java:prepareDisplayForRendering` + `Surface.setFrameRate`) already.

### 3.6 Better instrumentation

ImGui plots: frame time, queue size, soft cap, drop rate, host processing latency. This is the single most defensible reason users trust VoidLink's tuning — you can *see* the pacing working. Artemis's overlay is text and coarse (see §6).

### What VoidLink did **not** change

- No change to decoder configuration for latency beyond upstream.
- No change to the network/jitter path — `moonlight-common-c` is untouched in that respect.
- No input-path latency work (`91f95f1` actually *removed* a framerate limiter).
- No new lowest-latency mode. "Off" is upstream Moonlight iOS with `framePacing` unchecked.

---

## 4. What Artemis already does (do not re-add these)

### Already implemented and working

| Lever | Where | Status |
|---|---|---|
| Four frame-pacing modes | `PreferenceConfiguration.java:217-220` (`FRAME_PACING_MIN_LATENCY`/`BALANCED`/`CAP_FPS`/`MAX_SMOOTHNESS`), UI at `res/xml/preferences.xml:55` | Present but **overridden at runtime** — see §4.2 |
| `KEY_LOW_LATENCY` (Android 11+) | `MediaCodecHelper.java:550` (`videoFormat.setInteger("low-latency", 1)`) | Yes, always |
| `KEY_OPERATING_RATE` = `Short.MAX_VALUE` | `MediaCodecHelper.java:583` | Yes, on Qualcomm/RFI-capable decoders (`decoderSupportsMaxOperatingRate`) |
| `KEY_PRIORITY` = 0 (realtime) | `MediaCodecHelper.java:587` | Yes, as fallback when max operating rate is unsafe |
| Qualcomm vendor keys incl. **software fencing** | `MediaCodecHelper.java:616-622` (`vendor.qti-ext-dec-low-latency.enable`, `vendor.qti-ext-output-sw-fence-enable.value`, `vendor.qti-ext-output-fence.enable`, `.fence_type=1`) | Yes, but **gated** — see §4.3 |
| MediaTek / Kirin / Exynos / Amlogic / NVIDIA vendor keys | `MediaCodecHelper.java:540-698` | Yes |
| Progressive fallback if `configure()` rejects a key | `setDecoderLowLatencyOptions(..., tryNumber)` loop in `MediaCodecDecoderRenderer.java:761` | Yes |
| `Surface.setFrameRate` with `FIXED_SOURCE` + `CHANGE_FRAME_RATE_ALWAYS` | `Game.java:3821-3828` (`surfaceCreated`) | Yes, API 30/31+ |
| Display-mode selection / refresh-rate matching | `Game.java:1551-1630`, `prepareDisplayForRendering` | Yes |
| Latest-frame-only rendering (drain and present newest) | `MediaCodecDecoderRenderer.java:1213-1246` | Yes |
| Adaptive late-frame drop thresholds (EWMA jitter, backpressure, Hz mismatch) | `MediaCodecDecoderRenderer.java:1295-1420` | Yes — this *is* the "adaptive late-frame tolerance" in the project description |
| Decode-latency instrumentation (enqueue→dequeue by PTS) | `MediaCodecDecoderRenderer.java:56-95`, `updateDecodeLatencyStats` | Yes — this *is* the "latency instrumentation" |
| C2 decoder sleep watchdog | `MediaCodecDecoderRenderer.java:1479-1495` | Yes |
| Reference frame invalidation (AVC/HEVC/AV1) | `MediaCodecDecoderRenderer.java:409-440` | Yes |
| Slices-per-frame tuning | `MediaCodecHelper.java:758` | Yes |
| Urgent-display thread priorities | `startRendererThread`, `HandlerThread("Video - Choreographer", THREAD_PRIORITY_URGENT_DISPLAY)` | Yes |
| Perf overlay + lite overlay | `MediaCodecDecoderRenderer.java:1774-1885` | Yes |

**Git history check.** The project description's *"latency instrumentation, adaptive late-frame tolerance and configurable input batching"* — the first two are already in this branch's ancestry and are live code. Verified ancestors of `HEAD`:

- `18568d3` "Refactored MediaCodecDecoderRenderer: Measure decode latency (enqueue → dequeue) … Make dequeue timeout configurable"
- `1892147` "Unified decoder latency handling across all video pacing profiles"
- `3c8b960` "Reduce decoding latency on MTK: switch to non-blocking dequeue + latest-frame rendering … add a decoder watchdog"
- `88fe59a` "Add option for ultra low latency mode"
- `8cc3323` "Add forceTightThresholds option to reduce latency"
- `2e1ccbc` "Revert: Disable latest-only low latency path on NVIDIA decoders"

**There is no "configurable input batching" commit anywhere in `--all` history.** Nothing to recover; it was either never written or lives only in the description. Gamepad packet batching is done inside `moonlight-common-c` (submodule `ClassicOldSong/moonlight-common-c`) and is not exposed. `ControllerHandler.java:931` only references it in a comment about deadzones.

### 4.1 Not implemented

- **`preferMinimalPostProcessing`** — absent from the entire tree. `Window.setPreferMinimalPostProcessing(true)` (API 30+) asks the display to disable TV-style motion smoothing/noise reduction. On the AYN Thor's internal panel it is a no-op, but it matters when Artemis is cast to an external TV, which this fork supports ("External monitor mode", README feature 6).
- **`MATCH_CONTENT_FRAME_RATE`** — absent. Only relevant for Android TV.
- **Configurable input batching / coalescing** — absent, as above.
- **A queue-depth-style smoothing mode** — absent, and see §7; do not add one.

### 4.2 The frame-pacing setting is dead

`Game.java:690-704`:

```java
if (prefConfig != null && prefConfig.preferLowerDelays) {
    decoderRenderer.setPreferLowerDelays(true);
    decoderRenderer.setPreferLowerDelaysTimeoutUs(500);
    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;   // line 696
} else {
    decoderRenderer.setPreferLowerDelays(false);
    decoderRenderer.setPreferLowerDelaysTimeoutUs(2000);
    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;   // line 702
}
```

Both branches assign `FRAME_PACING_BALANCED`. The user-visible "Frame pacing" dropdown (`preferences.xml:55`, default `"latency"` per `PreferenceConfiguration.java:182`) is **discarded before it is ever used**. Downstream consumers read the overwritten value: the `FRAME_PACING_CAP_FPS` handling at `Game.java:757-773` can never fire, and `mayReduceRefreshRate()` (`Game.java:1446-1448`) always sees BALANCED.

### 4.3 The default render path runs two pacing policies at once

With the shipped defaults (`pref_low_latency_frame_balance` = false → `preferLowerDelays` = false), the renderer thread does this every iteration:

1. `MediaCodecDecoderRenderer.java:1213` — `if (!preferLowerDelays)`: non-blocking drain (`timeout 0`) keeping only the newest buffer, then present it immediately via `releaseWithPolicy(__last, System.nanoTime())`, then `continue`. **Lowest-latency policy.**
2. If step 1 found nothing, fall through to `MediaCodecDecoderRenderer.java:1252` — blocking `dequeueOutputBuffer(info, 2000µs)`. Because `prefs.framePacing` was forced to BALANCED, any frame found here goes into `outputBufferQueue` (depth limit 2, `:186`) and is presented later by the `Choreographer` callback at `:1069`. **Buffered policy, up to 2 frames of extra delay.**

Which policy a given frame gets depends on whether the renderer thread happened to be inside the 2 ms blocking dequeue when the decoder produced it. That is a coin flip per frame, and the two policies have different display latencies. **This is jitter that Artemis manufactures itself.** It is the closest thing to a genuine "VoidLink does it better" — not because VoidLink invented anything, but because VoidLink picks one policy and sticks to it.

Note also the flag naming is inverted from intuition: the *unchecked* "low latency frame balance" box gives you the hybrid; *checking* it gives you the coherent (Choreographer + immediate release) path.

### 4.4 The drain loop blocks for 2 ms per frame

`MediaCodecDecoderRenderer.java:1286`:

```java
while ((outIndex = videoDecoder.dequeueOutputBuffer(info, getOutputDequeueTimeoutUs())) >= 0) {
```

`getOutputDequeueTimeoutUs()` returns 2000 µs by default (`:63`, `:100`, set from `Game.java:701`). Upstream moonlight-android uses `0` here. This is a "is there another, newer frame waiting?" probe — it should never block. As written, every presented frame waits up to 2 ms for a successor that will not arrive. At 60 fps that is ~12% of a frame period, unconditionally, on every device.

The class comment at `:59-62` even says *"When preferLowerDelays=false we force 0µs (non-blocking, latest-frame rendering)"* — the code does not do that.

### 4.5 Two dead settings

- **`checkbox_forceTightThresholds`** exists in `preferences.xml:76-80` with strings, but `PreferenceConfiguration.readPreferences()` never reads the key. `config.forceTightThresholds` is hardcoded `false` at `PreferenceConfiguration.java:233`. `Game.java:673-687` then reads it back **via reflection** (on a field it could reference directly) and calls `setForceTightThresholds`. And in the renderer, `forceTightThresholds` is **set and never read** (`MediaCodecDecoderRenderer.java:53-55` are its only three occurrences). The whole chain is inert.
- **`MediaCodecHelper.applyExtraVendorOptions()`** (`:1161-1194`) has no callers anywhere in the tree.

---

## 5. Implementable changes, ranked

Ranked by (expected latency benefit) ÷ (risk). Target device assumed: **AYN Thor**, Snapdragon 8 Gen 2, 6" 1080p **120 Hz** AMOLED, host RTX 4070 + Apollo.

### Tier 1 — high benefit, low risk

**1. Make the drain-loop dequeue non-blocking (2000 µs → 0).**
- File: `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java:1286`, and the `getOutputDequeueTimeoutUs()` contract at `:59-63`.
- Change type: **one-line default change** (make the inner drain use literal `0`; leave the outer blocking dequeue at `:1252` alone — that one *should* block, it is the idle wait).
- Expected: up to **2 ms** off every frame, unconditionally. Matches upstream moonlight-android.
- Risk: near zero. Restores upstream behaviour.
- Measure: **Average decoding time** in the perf overlay will *not* move (it measures enqueue→dequeue only). Use the **Rendering frame rate** vs **Incoming frame rate** pair — they should stay locked together — plus an external measurement (§6).

**2. Stop forcing `FRAME_PACING_BALANCED`; honour the user's setting.**
- File: `app/src/main/java/com/limelight/Game.java:696` and `:702`.
- Change type: **delete two lines** (default change).
- Effect: the default preference is `"latency"` → `FRAME_PACING_MIN_LATENCY`, which routes every frame down the latest-only path at `:1213` and never touches `outputBufferQueue`. Removes the per-frame coin flip in §4.3 and removes up to 2 frames (~16 ms at 120 Hz, ~33 ms at 60 fps) of worst-case buffering.
- Risk: **medium-low**. It genuinely changes shipped behaviour, and `FRAME_PACING_CAP_FPS` handling at `Game.java:757-773` becomes live again after years of being dead — that path mutates `chosenFrameRate` and must be re-tested. Do this second, after change 1, so the two effects are separable.
- Measure: **Rendering frame rate** should equal **Incoming frame rate** at steady state; watch that **Frames dropped by your network connection** does not rise (it should not — that counter is network loss, not pacing drops).

**3. Turn on the "Ultra low latency" checkbox by default on Qualcomm — or at minimum, document that it is off.**
- Files: `PreferenceConfiguration.java:143` (`DEFAULT_ENABLE_ULTRA_LOW_LATENCY = false`), `preferences.xml:68-73`.
- What it actually does: at `MediaCodecHelper.java:554-556`, when the flag is **false** and the decoder advertises `FEATURE_LowLatency`, `setDecoderLowLatencyOptions` sets only `low-latency=1` and **returns early**. On a Snapdragon 8 Gen 2 with `c2.qti.*` decoders — which do advertise `FEATURE_LowLatency` on Android 13+ — that early return **skips** `KEY_OPERATING_RATE`, `KEY_PRIORITY`, and the entire Qualcomm block at `:616-622` including `vendor.qti-ext-output-sw-fence-enable.value`. The in-repo comment on those keys reads *"CONFIRMED WORKING: Snapdragon Elite, SD8 gen 3, SD8 gen 2 — latency-wise, software fencing is the most important flag for latest Snapdragons."*
- Change type: **default change** (flip to true), or better, condition it on `isDecoderInList(qualcommDecoderPrefixes, ...)`.
- Expected: this is the largest single decoder-side win available on the Thor. Plausibly several ms of decode-to-surface.
- Risk: **medium**. It is off by default for a reason — vendor keys can make `configure()` fail. But the `tryNumber` fallback loop at `MediaCodecDecoderRenderer.java:761` exists precisely to recover from that, and the flag is already user-exposed and presumably field-tested. Note the name is misleading: despite `StreamConfiguration.setEnableUltraLowLatency`, it is **client-side only** and is never sent to Apollo (`NvHTTP.java:883` does not include it).
- Measure: **Average decoding time** in the perf overlay. This is the one change the overlay *can* see. Expect it to drop.

### Tier 2 — worth doing, smaller or narrower effect

**4. Add `Window.setPreferMinimalPostProcessing(true)`.**
- File: `app/src/main/java/com/limelight/Game.java`, in `onCreate` after the window flags are set. **New code**, ~4 lines, API 30 guard.
- Expected: **zero on the Thor's internal panel.** Meaningful (10–100 ms) only when output goes to an external TV/monitor that applies motion interpolation — which this fork explicitly supports.
- Risk: negligible.
- Measure: not visible in the overlay at all. Only measurable end-to-end with a camera against the external display.

**5. Fix or delete the `forceTightThresholds` chain.**
- Files: `PreferenceConfiguration.java:233`, `preferences.xml:76-80`, `Game.java:673-687`, `MediaCodecDecoderRenderer.java:53-55`.
- Either wire it (read the pref; use `forceTightThresholds` to pin `periodNs = vsyncPeriodNs` at `MediaCodecDecoderRenderer.java:1184` instead of `max(vsync, streamPeriod)`) or remove the setting so users stop toggling a placebo.
- On a 120 Hz display streaming 60 fps, `periodNs` currently resolves to the 60 fps stream period (16.7 ms) rather than the 8.3 ms vsync period, which makes every late-frame threshold at `:1295-1420` twice as tolerant as it looks. Pinning to vsync tightens drop decisions.
- Risk: **medium** if wired — tighter thresholds mean more dropped frames, which is the correct trade for latency but will be visible. Ship it as a setting, off by default, exactly as the UI already promises.
- Measure: **Rendering frame rate** will fall slightly below **Incoming frame rate** when it engages. That is expected and is the point.

**6. Delete `MediaCodecHelper.applyExtraVendorOptions()` (`:1161-1194`) or call it.**
- Dead code that duplicates keys already set at `:540-698`. Deleting is the safe move; calling it risks double-setting `picture-order.enable` with a *different* value (0 vs 1) than the live path uses for C2 decoders.

### Tier 3 — measurement, not latency

**7. Add pacing-aware numbers to the perf overlay.** See §6. Without these, none of the above can be honestly evaluated on-device, and the team will be arguing from feel — which is exactly how the VoidLink claim got started.

### Explicitly not recommended for this device

- Anything about `Surface.setFrameRate` — already correct at `Game.java:3821-3828`. The duplicate reflection-based call at `Game.java:904-932` is redundant but harmless; leave it during the UI restyle.
- Switching to AV1. `findAv1Decoder` (`MediaCodecDecoderRenderer.java:328`) deliberately returns `null` unless the user picks "Force AV1", and the default is `"auto"` → HEVC. That default is correct for latency: Adreno 740 AV1 decode is slower than its HEVC path, and the RTX 4070's AV1 encoder gains you bitrate efficiency, not latency. "AV1-capable" is not a latency lever here.

---

## 6. How to measure any of this

**Read this before trusting the perf overlay.** Two of its numbers are not what their labels say:

- **"Average decoding time"** (`perf_overlay_dectime`) is computed at `MediaCodecDecoderRenderer.java:1791` as `decoderTimeMs / totalFramesReceived`, where `decoderTimeMs` comes only from `updateDecodeLatencyStats` — an **enqueue-to-dequeue** measurement (`:83-96`). It measures the hardware decoder and **nothing after it**. Frame pacing, queue depth, `releaseOutputBuffer` scheduling, and compositor delay are all invisible to it. Changes 1, 2 and 5 will not move this number even if they work.
- **"Average end-to-end client latency"** (crash-report only, `getAverageEndToEndLatency()` at `:2231`) is `totalTimeMs / totalFramesReceived`, and `totalTimeMs` is fed from the same enqueue→dequeue delta because `USE_FRAME_RENDER_TIME = false` (`:102`). It is a duplicate of the decoder number, not an end-to-end figure. Do not cite it.

So, per change:

| Change | Overlay number that should move | Ground truth needed |
|---|---|---|
| 1 (drain timeout → 0) | none | External: camera at 240 fps, or an LDAT-style click-to-photon rig |
| 2 (honour frame pacing pref) | **Rendering frame rate** should track **Incoming frame rate** exactly; variance should collapse | External photon measurement |
| 3 (ULL default on) | **Average decoding time** — should drop | Overlay is sufficient |
| 5 (tight thresholds) | **Rendering frame rate** dips below incoming under jitter | Overlay is sufficient to confirm engagement |

The cheap external rig: run an on-screen millisecond timer on the host, photograph host and handheld screens together at 1/1000 s, subtract. Twenty samples gives a usable mean. Alternatively film a mouse-click-to-cursor-move at 240 fps and count frames.

**Recommended overlay additions** (`MediaCodecDecoderRenderer.java:1774-1885`), which would make this repo self-sufficient for tuning and would match what VoidLink's ImGui plots give its users:

1. `outputBufferQueue.size()` sampled per second — instantly shows whether the Choreographer path is being used at all.
2. A **present-path counter**: how many frames took the `:1213` latest-only path vs. the `:1439` queue path, per second. This makes the §4.3 hybrid visible; it should read 100/0 after change 2.
3. **Dequeue-to-release delta**, i.e. time from `dequeueOutputBuffer` returning to `releaseOutputBuffer` being called. This is the pacing delay that "Average decoding time" hides.
4. **Frames dropped by pacing** as a separate counter from `perf_overlay_netdrops` (which is network loss). Currently a pacing drop is invisible.

---

## 7. What NOT to do

**Do not port VoidLink's `FrameQueue`.** It is the headline feature and it is a latency *cost*. A depth-2 decoded-frame buffer at 60 fps is ~33 ms of added display delay. Artemis's `outputBufferQueue` (depth 2, `:186`) is already the same idea and the recommendation above is to stop using it, not to make it configurable. If someone argues "but VoidLink users say it's smoother" — yes, and VoidLink's own help text says the mode they are describing may be chosen *instead of* the low-latency modes.

**Do not add frame interpolation.** No Android equivalent to `VTFrameProcessor`, and it structurally requires holding frame N+1 before presenting the tween. It buys apparent smoothness with real latency. Wrong direction.

**Do not "fix" dropped frames by buffering them.** Every drop at `MediaCodecDecoderRenderer.java:1314`, `:1344`, `:1387`, `:1418` is a frame that arrived too late to be shown on time. Displaying it late does not recover the information; it delays every subsequent frame. **A dropped frame is a latency payment already made. Buffering converts a one-frame visual glitch into a permanent one-frame delay.** Any proposal phrased as "never drop frames" is a proposal to add latency.

**Do not enable `FRAME_PACING_MAX_SMOOTHNESS` or `CAP_FPS` as the default** once change 2 makes the dropdown live. Both take the never-drop branch at `:1295`.

**Do not set `KEY_PRIORITY = 0` together with `KEY_OPERATING_RATE = Short.MAX_VALUE`.** The comment at `MediaCodecHelper.java:515-525` documents reliable crashes on Snapdragon 765G / Xiaomi Mi 10 Lite when the decoder cannot satisfy a realtime priority at an absurd operating rate. The current code correctly picks one or the other. Leave it.

**Do not raise the AV1 default.** See §5.

**Do not chase VoidLink's Metal renderer.** Its purpose is HDR/EDR metadata and overlay compositing, plus working around `AVSampleBufferDisplayLayer` bugs (`958b391`, `5672946`). Artemis already renders directly to a `Surface` from `MediaCodec`, which is strictly fewer copies than VoidLink's Metal path. There is nothing to gain.

**Do not add a "prebuffer on start" behaviour.** VoidLink has one (`_needRequeuing` in `renderModeAVSB:`) and it is a startup-smoothness feature that costs steady-state latency after every stall.

**Do not change more than one thing at a time.** Changes 1, 2 and 3 have overlapping effects on the same code path; measured together they will be indistinguishable, and if one regresses you will not know which.

---

## 8. Summary table

| Lever | Artemis has it? | Exposed as a setting? | Default conservative? | Would it help on the Thor? |
|---|---|---|---|---|
| Latest-frame-only rendering | Yes | No (forced) | Runs, but only as half a hybrid | Yes, once made exclusive |
| Non-blocking output drain | No — blocks 2 ms | No | Regression vs upstream | Yes, ~2 ms |
| Frame-pacing mode choice | Yes, four modes | Yes, but discarded | N/A — inert | Yes, once honoured |
| `KEY_LOW_LATENCY` | Yes | No | Always on | Already applied |
| `KEY_OPERATING_RATE` / `KEY_PRIORITY` | Yes | Via "Ultra low latency" | **Skipped by default on SD8G2** | Yes |
| Qualcomm software fencing | Yes | Via "Ultra low latency" | **Skipped by default on SD8G2** | Yes, notably |
| `Surface.setFrameRate` | Yes | No | Correct | Already correct |
| `preferMinimalPostProcessing` | No | No | N/A | Only on external displays |
| Tight (vsync) drop thresholds | Code present, chain dead | Checkbox exists, inert | Inert | Yes, as an opt-in |
| Decoded-frame queue smoothing | Yes (depth 2) | No | Active by default | **No — remove its use** |
| Frame interpolation | No | No | N/A | **No — do not add** |
| Configurable input batching | No, and never existed | No | N/A | Unknown; unmeasured, low priority |
| Pacing-aware instrumentation | No | N/A | N/A | Prerequisite for all of the above |
