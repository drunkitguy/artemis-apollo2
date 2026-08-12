# 05 — Dynamic Bitrate: Adversarial Review and Build Decision

**Status:** research note. Decides whether `media/` and `ui/` get a bitrate adaptation
feature. **Answer: no closed loop. Yes to a bounded subset — see §6.**

**Subject under review:** `moonlight_dynamic_bitrate_v2.md` ("Revision v2 — rewritten after
adversarial review of v1"). Every claim below was checked against source, not against
prose. Primary sources are Sunshine `master`, Apollo `master`, `moonlight-common-c`
`master`, ClassicOldSong's `moonlight-common-c` fork (the one Artemis actually builds),
Artemis `v20.3.0-experimental.9`, and Moonlight Qt `master`, all as of 2026-08-12.

This file also corrects **two errors in our own `01-PROTOCOL.md`** that the review turned
up. See §5.

---

## 1. Verdicts on the attacked claims

### 1.1 "There is no closed-loop adaptive bitrate in this stack" — **UPHELD**, with three corrections

The conclusion survives. The reasoning behind it does not, and the difference matters for
what we build.

**Verified absent, everywhere:**

*No control message.* Sunshine's outbound/inbound control type table is 19 entries and
contains nothing bitrate-related
([`src/stream.cpp`](https://github.com/LizardByte/Sunshine/blob/master/src/stream.cpp),
`packetTypes[]`). Apollo's is the same list plus three Apollo extensions — `0x3000` Execute
Server Command, `0x3001` Set Clipboard, `0x3002` File Transfer Nonce Request
([`ClassicOldSong/Apollo/src/stream.cpp`](https://github.com/ClassicOldSong/Apollo/blob/master/src/stream.cpp)).
Artemis's `moonlight-common-c` fork adds exactly those same three and nothing else
([`ClassicOldSong/moonlight-common-c/src/ControlStream.c`](https://github.com/ClassicOldSong/moonlight-common-c/blob/master/src/ControlStream.c),
`IDX_EXEC_SERVER_CMD`/`IDX_SET_CLIPBOARD`/`IDX_FILE_TRANSFER_NONCE_REQUEST`, indices 12–14).

*No inbound handler that could act on one.* Both hosts register exactly eight inbound
handlers: `PERIODIC_PING`, `START_A`, `START_B`, `LOSS_STATS`, `REQUEST_IDR_FRAME`,
`INVALIDATE_REF_FRAMES`, `INPUT_DATA`, `ENCRYPTED` (Apollo adds `EXEC_SERVER_CMD`,
`SET_CLIPBOARD`, `FILE_TRANSFER_NONCE_REQUEST`). Unrecognised types are dropped.

*No RTSP renegotiation path.* Both hosts map five verbs only — `OPTIONS`, `DESCRIBE`,
`SETUP`, `ANNOUNCE`, `PLAY`
([Sunshine `src/rtsp.cpp:1337-1341`](https://github.com/LizardByte/Sunshine/blob/master/src/rtsp.cpp),
[Apollo `src/rtsp.cpp:1188-1192`](https://github.com/ClassicOldSong/Apollo/blob/master/src/rtsp.cpp)).
No `SET_PARAMETER`, no re-`ANNOUNCE`. The encoder is constructed from
`config.monitor.bitrate` captured once in `cmd_announce`.

*No client API.* The complete `Li*` surface in
[`Limelight.h`](https://github.com/ClassicOldSong/moonlight-common-c/blob/master/src/Limelight.h)
contains `LiStartConnection`, `LiStopConnection`, `LiRequestIdrFrame`,
`LiGetEstimatedRttInfo`, `LiGetPendingVideoFrames`, input senders, and `LiSendExecServerCmd`.
There is no `LiSetBitrate` or equivalent, in upstream or in Apollo's fork.

*Feature requests confirmed open/rejected.*
[moonlight-qt #802 "Dynamic bitrate/congestion control"](https://github.com/moonlight-stream/moonlight-qt/issues/802) —
closed **not planned**, 2022.
[moonlight-qt #1618 "Add option to auto adjust bitrate based on connection speed"](https://github.com/moonlight-stream/moonlight-qt/issues/1618) —
open, last touched 2026-07-14, unimplemented.
[moonlight-qt #1708 "Dynamic / Unlimited Video Bitrate"](https://github.com/moonlight-stream/moonlight-qt/issues/1708) —
open.
[Apollo #399 "Adaptive Bitrate Scaling for Smooth Streaming"](https://github.com/ClassicOldSong/Apollo/issues/399) —
**open** since 2025-02-20, unimplemented. (Four comments exist on this issue that I could
not read — the session's GitHub API access does not cover that repo and the issue page does
not render comments to WebFetch. The *state* is verified open; the maintainer's stated
reasoning is **UNVERIFIED**.)

**Correction 1 — "Nothing measures the network" is false.** The client measures
continuously; what it lacks is an actuator. `moonlight-common-c` computes per-interval
frame loss and drives a two-state connection estimator
([`ControlStream.c:486-509`](https://github.com/ClassicOldSong/moonlight-common-c/blob/master/src/ControlStream.c)),
maintains an RTT estimate exposed via `LiGetEstimatedRttInfo`, sends loss stats (`0x0201`)
and per-frame FEC status (`0x5502`) to the host. Saying "nothing measures" invites the wrong
conclusion — that we would have to build measurement from scratch. We would not; we would be
building *actuation*, which is the part that does not exist.

**Correction 2 — the "min == max latches off adaptation" story is about GFE, not Sunshine.**
The primary source is explicit:

> `// We don't support dynamic bitrate scaling properly (it tends to bounce between min and max and never`
> `// settle on the optimal bitrate if it's somewhere in the middle), so we'll just latch the bitrate`
> `// to the requested value.`
> — [`SdpGenerator.c:356-358`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/SdpGenerator.c)

The adaptive machinery being latched off belongs to **GeForce Experience**. Sunshine and
Apollo never implemented one, so there is nothing for `min == max` to disable there. Two
useful implications the document misses: (a) closed-loop bitrate adaptation *was* built
once in this protocol family, and (b) it was disabled because it **oscillated** — the exact
failure mode any loop we build has to answer for. That is the single most relevant sentence
in the whole corpus and the document does not cite it.

**Correction 3 — the client's feedback to Apollo is write-only.** Sunshine and Apollo read
`x-ml-general.featureFlags` but consult it *only* for `ML_FF_SESSION_ID_V1`; `ML_FF_FEC_STATUS`
is never tested. `0x5502` has no inbound handler (in both hosts `0x5502` is the *outbound*
"Set RGB LED" extension). `IDX_LOSS_STATS` has a handler that does nothing but
`BOOST_LOG(verbose)`. **Nothing we report to Apollo changes Apollo's behaviour.** Only
`REQUEST_IDR_FRAME` and `INVALIDATE_REF_FRAMES` actually cause host-side action.

### 1.2 "On a stable wired LAN you should not want dynamic bitrate — adaptation can only ever lower quality" — **REFUTED as stated**

Two separate defects.

**It is a tautology disguised as a finding.** "On a link that never degrades, adaptation is
a pure loss" is true by construction. The interesting question — *how often is a link that
claims to be stable actually stable* — is assumed away. The document's own §9 concedes
"WiFi is the largest latency variable", and our target is Android handhelds, which are
overwhelmingly WiFi. The premise does not hold for our users.

**"Adaptation can only ever lower quality" is false for any two-sided loop.** The claim
silently assumes a ratchet-down design. A loop with a probe-upward arm raises quality when
headroom appears — which is exactly what issue #1708 ("I have a 5 Gbps link and want to use
it") is asking for. The document conflates *the specific bad loop GFE shipped* with *the
class of loops*.

**Failure modes it does not address, all of which occur on a wired LAN:**

- **Encoder overrun.** Host processing latency rises independently of the network; lowering
  bitrate reduces encode work. The document's own §7 tells you to watch host processing
  latency, then §0 tells you adaptation cannot help — inconsistent.
- **Decoder overrun and thermal throttling.** On a handheld the decoder budget shrinks over
  a session as the SoC heats. This is a *time-varying* client constraint, and the document's
  §1 (identify the binding term) correctly identifies the decoder as a possible binding term
  but then treats it as a static number to look up once.
- **Encoder burst overshoot.** The document itself recommends `nvenc_vbv_increase = 100` on
  LAN, against Sunshine's own explicit warning that this option "can lead to network packet
  loss". It recommends the burst-risk setting and rejects the mechanism that would manage it.
- **Competing LAN traffic.** A gigabit link running a 120 Mbps stream has headroom until
  something else on the segment does not.

**What survives:** on a genuinely stable wired link with a decoder that is not the binding
term, a well-chosen static bitrate is better than any loop, because the loop's actuator in
this stack is a stream restart (§4). That narrower claim is correct and is the reason our
recommendation still lands mostly where the document lands.

### 1.3 Transport overhead — "slider is video only, FEC adds 20%, 100 Mbps ⇒ 120–125 Mbps" — **REFUTED**

This is the document's self-declared "most useful number" and it is backwards by ~25%. Three
independent primary sources:

**The client's own header documentation:**

> `// Bitrate of the desired video stream (audio adds another ~1 Mbps). This`
> `// includes error correction data, so the actual encoder bitrate will be`
> `// about 20% lower when using the standard 20% FEC configuration.`
> `int bitrate;`
> — [`Limelight.h:52-55`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Limelight.h)

**The client deducts it before announcing:**

> `// 20% of the video bitrate will added to the user-specified bitrate for FEC`
> `adjustedBitrate = (int)(StreamConfig.bitrate * 0.80);`
> — [`SdpGenerator.c:336-337`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/SdpGenerator.c)

**The host deducts it again from the raw value, plus audio, plus overhead:**

```cpp
// Sunshine src/rtsp.cpp:1254-1273 — identical in Apollo src/rtsp.cpp:1113-1132
if (configuredBitrateKbps) {
  if (config::stream.fec_percentage <= 80) {
    configuredBitrateKbps /= 100.f / (100 - config::stream.fec_percentage);
  }
  auto audioBitrateAdjustment = (HIGH_QUALITY ? 256 : 96) * config.audio.channels;
  configuredBitrateKbps -= std::min(audioBitrateAdjustment, configuredBitrateKbps / 5);
  configuredBitrateKbps -= std::min((std::int64_t) 500, configuredBitrateKbps / 10);
  config.monitor.bitrate = configuredBitrateKbps;
}
```

**The slider is a total wire budget, not a video-only figure.** A 100 Mbps setting yields
roughly 80 Mbps of *encode*; add 20% parity (≈96 Mbps), plus ~0.5 Mbps audio and headers,
and you land at approximately the slider value. Real wire rate is ~96–98% of the slider,
because the host uses `× (100−fec)/100 = ×0.80` where exact FEC accounting would be
`÷1.20 = ×0.833` — a small conservative margin, not a 20–25% surcharge.

`fec_percentage` default **20** is confirmed
([`config.cpp:812`](https://github.com/LizardByte/Sunshine/blob/master/src/config.cpp),
[docs](https://github.com/LizardByte/Sunshine/blob/master/docs/configuration.md)), so that
part of the claim is right. Everything built on top of it is wrong.

**Actionable consequence for us — this is the highest-value finding in the review.** Apollo
and Sunshine differ in one place:

```cpp
// Apollo src/rtsp.cpp:1044-1048 — NOT present in Sunshine
configuredBitrateKbps = util::from_view(args.at("x-ml-video.configuredBitrateKbps"sv));
if (!configuredBitrateKbps) {
  configuredBitrateKbps = config.monitor.bitrate;   // == maximumBitrateKbps, already ×0.80
}
```

Sunshine skips the whole adjustment block when `configuredBitrateKbps` is absent. Apollo
falls back to `maximumBitrateKbps` — which the client already reduced to 80% — and then
applies `×0.80` **again**. If our ANNOUNCE omits `x-ml-video.configuredBitrateKbps`, Apollo
encodes at **0.64× the user's setting**. `01-PROTOCOL.md` §6.4 already lists this attribute;
this note is why it is not optional against our user's host. Send the **raw user value**,
not the adjusted one.

Also relevant: FEC percentage is a **host-side** setting against Sunshine/Apollo. The client
only sends `x-nv-vqos[0].fec.repairPercent` in the **GFE branch** of `SdpGenerator.c`
(≈lines 225-230), and there the condition is *resolution* — `5` at 4K, `20` otherwise — not
LAN vs WAN.

### 1.4a "4K needs ~3× rather than 4× the bits of 1080p" — **REFUTED / internally contradictory**

Moonlight's own default table is pixel-linear:

```cpp
// moonlight-qt app/settings/streamingpreferences.cpp:375-386
{ 1280 *  720,  5 },
{ 1920 * 1080, 10 },
{ 2560 * 1440, 20 },
{ 3840 * 2160, 40 },
```

with `frameRateFactor = fps/30` below 60 fps, so 1080p60 = 20 Mbps and 4K60 = 80 Mbps —
**exactly 4×**, by linear interpolation on pixel count
([source](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp);
the same table is in `PreferenceConfiguration.getDefaultBitrate` in Moonlight/Artemis for
Android). The document quotes these very defaults ("Moonlight's default is 20 Mbps",
"Moonlight's default is 80 Mbps") and then, one section later, declares the 4× relationship
they encode to be wrong — without citing anything.

The underlying physics ("compression efficiency improves with resolution") is real and
uncontroversial. The specific substitution of ~3× for 4× is **UNSOURCED**, and the document's
own recommended ranges (40–50 → 100–150 Mbps) actually span 2.0–3.75×, so it does not even
implement its own correction consistently. A reversal of a previous position with no
evidence behind it is not a correction; it is a second guess.

### 1.4b "H.264 may beat HEVC/AV1 on a LAN because decode time is real latency" — **PARTIALLY REFUTED**

The premise is sound: decode time is genuinely client-side latency, and it does vary by
codec and by SoC. The prescription is not supported and conflicts with the rest of the same
document.

**Moonlight's own automatic selection does the opposite:**

> `// Codecs are checked in order of ascending decode complexity to ensure`
> `// the the deprioritized list prefers lighter codecs for software decoding`
> `// H.264 is already the lowest priority codec, so we don't need to do`
> `// any probing for deprioritization for it here.`
> — [`moonlight-qt app/streaming/session.cpp:749-753`](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/streaming/session.cpp)

`VCC_AUTO` prefers AV1, then HEVC, and treats H.264 as the fallback whenever hardware decode
is available. That is the only decoder-selection heuristic in the stack and it contradicts
"often H.264".

**Two internal contradictions in the document:**

1. §9 advises "Enable HDR on the client where available… it unlocks 10-bit". There is no
   10-bit H.264 in this protocol. `Limelight.h:221-230` defines `VIDEO_FORMAT_H264` (High)
   and `VIDEO_FORMAT_H264_HIGH8_444` — both 8-bit. 10-bit exists only as
   `VIDEO_FORMAT_H265_MAIN10`, `VIDEO_FORMAT_H265_REXT10_444`, `VIDEO_FORMAT_AV1_MAIN10`,
   `VIDEO_FORMAT_AV1_HIGH10_444`. Choosing H.264 forecloses the 10-bit banding fix the same
   document recommends.
2. §5 recommends `nvenc_split_encode` at 4K. Sunshine's docs: *"This option only applies when
   using NVENC encoder **with HEVC or AV1**."* Split encode and H.264 are mutually exclusive.

**Verdict:** treat "measure decode time on the actual device" as the finding (correct), and
"often H.264" as an unsupported generalisation (drop it). For our client this argues for
per-device probing at ANNOUNCE time, which we do anyway.

### 1.4c "Withdraw the constant-QP recommendation" — **UPHELD**, mechanism overstated

Right conclusion. Sunshine's `qp` is documented as a *fallback*: *"Some devices don't support
Constant Bit Rate. For those devices, QP is used instead."* Default **28**, not the 20 that
v1 apparently recommended.

The cited failure mode is real and I found it:

```cpp
// Sunshine src/stream.cpp — and identically in Apollo
if (fec_blocks_needed > MAX_FEC_BLOCKS) {
  BOOST_LOG(warning) << "Skipping FEC for abnormally large encoded frame (needed "
                     << fec_blocks_needed << " FEC blocks)";
  fecPercentage = 0;
}
...
if (aligned_size / blocksize >= 1024) {
  BOOST_LOG(error) << "Encoder produced a frame too large to send! Is the encoder broken?";
}
```

But it is **not QP-specific**. `MAX_FEC_BLOCKS` is 4 and the code's own comment says this
"should only happen for enormous frames (over 800 packets at 20%)" — any rate-control mode
that overshoots badly enough triggers it. CQP makes it far more likely; it does not uniquely
cause it. The second branch is worse and the document does not mention it: past 4096 packets
in a block, the frame is simply unrecoverable.

### 1.4d "Do not lower `fec_percentage` on a LAN" — **UPHELD as advice, wrong reason**

The advice is right. The stated reason — that you "reclaim bandwidth" which is "worth nothing
on a gigabit LAN" — is not what happens. Because the host derives the encode bitrate as
`configured × (100 − fec)/100`, lowering `fec_percentage` **raises the video encode bitrate**
to refill the same total budget. Total wire rate is roughly unchanged. You are not trading
bandwidth for protection; you are trading protection for image quality at constant bandwidth
— which is a more interesting trade than the document describes, and still usually a bad one
on a link where a transient costs you a visible artefact and an IDR round-trip.

Separately: this is not a client-side knob against Apollo at all (§1.3). It is a host config
value. We cannot set it and should not expose it.

### 1.5 `nvenc_vbv_increase` semantics — **UPHELD**, essentially verbatim

Sunshine's documentation:

> *"Single-frame VBV/HRD percentage increase. By default Sunshine uses single-frame VBV/HRD,
> which means any encoded video frame size is not expected to exceed requested bitrate divided
> by requested frame rate. Relaxing this restriction can be beneficial and act as low-latency
> variable bitrate, but may also lead to packet loss if the network doesn't have buffer
> headroom to handle bitrate spikes. **Maximum accepted value is 400, which corresponds to 5x
> increased encoded video frame upper size limit.**"*
> — [`docs/configuration.md`](https://github.com/LizardByte/Sunshine/blob/master/docs/configuration.md)

Confirmed in code:

```cpp
// src/nvenc/nvenc_base.cpp:278-283
enc_config.rcParams.vbvBufferSize = client_config.bitrate * 1000 / client_config.framerate;
if (config.vbv_percentage_increase > 0) {
  enc_config.rcParams.vbvBufferSize += enc_config.rcParams.vbvBufferSize * config.vbv_percentage_increase / 100;
}
```

and `int_between_f(vars, "nvenc_vbv_increase", video.nv.vbv_percentage_increase, {0, 400})`
([`config.cpp:1589`](https://github.com/LizardByte/Sunshine/blob/master/src/config.cpp)),
default 0. Single-frame VBV, 400 max, 5× — all three exact.

"Content-adaptive but not network-adaptive" is the right characterisation, and if anything
too generous: it is not adaptive at all, it is a *relaxed constraint* that permits the rate
controller to spend more on hard frames. Open-loop, as stated. The document's warning that it
raises peak load precisely when motion is highest is correct and matches Sunshine's own
`@warning{Can lead to network packet loss.}`.

**One omission:** the option is NVENC-only ("This option only applies when using NVENC
encoder"). The document's tables present it as a universal knob. AMD and Intel hosts have
different levers (`amd_enforce_hrd`, `amd_rc`, `vaapi_strict_rc_buffer`).

### 1.6 Unsourced or unsupported claims

| Claim | Verdict |
|---|---|
| "Add roughly 10–20% for HDR / 10-bit" | **UNVERIFIED.** No source. Moonlight's own default calculator adds **nothing** for HDR — the only multiplier in `getDefaultBitrate` is `×2` for YUV 4:4:4. |
| "Diminishing visual returns starting around 120 [Mbps at 4K]" | **UNVERIFIED.** No source, no method. |
| "4K60 over WAN viable with 100+ Mbps symmetric fibre and AV1" | **UNVERIFIED.** Asserted with no evidence; softening a v1 prohibition on a hunch. |
| "`nvenc_split_encode` … the driver enables it automatically at 4K with presets P1–P4" | **UNVERIFIED.** Sunshine defaults to `driver_decides` → `NV_ENC_SPLIT_AUTO_MODE` and states no condition. The P1–P4/4K rule is not in Sunshine's docs or source; it may come from NVIDIA's NVENC SDK, which I did not confirm. Also HEVC/AV1 only. |
| "FEC defaults to 20%, matching what GeForce Experience used" | **Partly wrong.** The 20% default is confirmed. The GFE attribution is doubtful: `moonlight-common-c` sends GFE `repairPercent = 5` at 4K and `20` otherwise. |
| "2.4 GHz sustains 720p at modest bitrates" | **UNVERIFIED**, plausible. |
| "A webOS TV client caps at 65 Mbps and degrades above ~45 Mbps" | **UPHELD**, with attribution correction: this is `mariotaku/moonlight-tv`, a third-party webOS client, not Moonlight proper — [issue #346](https://github.com/mariotaku/moonlight-tv/issues/346), [issue #518](https://github.com/mariotaku/moonlight-tv/issues/518). |
| "Moonlight's on-screen warning fires on frame loss" | **UPHELD.** `ControlStream.c:486-509` drives it purely from `frameLossPercent`. |
| "Sunshine's docs frame preset changes as the thing to do when limited by network or decoder" | **UPHELD**, near-verbatim: *"Recommended to change only when limited by network or decoder, otherwise similar effect can be accomplished by increasing bitrate."* |
| "Apollo's guidance recommends Balanced frame pacing on clients with ultra-low-latency mode" | **UPHELD**, verbatim: *"For devices that supports 'Ultra Low Latency' mode on Artemis, choose 'Balanced' for frame pacing. Latency doesn't differ much from Warp modes when Ultra Low Latency is enabled and it's more smooth."* — [Apollo wiki, Best Practices](https://github.com/ClassicOldSong/Apollo/wiki/Best-Practices) |

---

## 2. What the document gets wrong

1. **The transport overhead number is inverted** (§1.3). This is the claim it advertises as
   most useful and it would have caused us to over-provision every bandwidth estimate in the
   UI by ~25%.
2. **The 4× → 3× "correction" contradicts the only source it cites** (§1.4a) and is unsupported.
3. **The H.264-on-LAN prescription contradicts Moonlight's own codec heuristic and two of the
   document's own recommendations** (§1.4b).
4. **"Adaptation can only ever lower quality" is false** for any loop with a probe-upward arm,
   and the stable-LAN premise does not describe our users (§1.2).
5. **It misses the one primary source that actually settles the argument** — the
   `SdpGenerator.c` comment recording that dynamic bitrate scaling *was* implemented in this
   protocol family and was latched off because it oscillated. A document whose thesis is
   "adaptation does not exist here" should know that it existed and why it was abandoned.
6. **It attributes the min/max latch to Sunshine.** It is a GFE behaviour; Sunshine never had one.
7. **It says nothing measures the network.** The client measures continuously (§1.1,
   Correction 1). What is missing is actuation, not telemetry.
8. **`nvenc_vbv_increase` is presented as universal**; it is NVENC-only.
9. **Several load-bearing numbers are unsourced** (HDR +10–20%, 120 Mbps knee, split-encode
   auto conditions).

## 3. What the document gets right

Credit where due — the hardened parts held up well.

- **The central thesis survives full source verification.** No bitrate control message, no
  RTSP renegotiation, no client API, in Sunshine, Apollo, Moonlight, or Artemis. I tried hard
  to break this and could not.
- **§1 "identify the binding term first"** — `min(decoder, encoder, network)` — is the best
  idea in the document and is the correct frame for our own design. Most "my stream is bad"
  reports are decoder-bound or encoder-bound, and no network work helps.
- **`nvenc_vbv_increase` semantics are exact** (§1.5), including the 400/5× relationship and
  the burst-risk framing.
- **UDP-mode `iperf3` over TCP throughput testing** is right, and for the right reason: jitter
  and loss break remote play, peak average bandwidth does not.
- **Withdrawing constant-QP** is right (§1.4c).
- **Not lowering `fec_percentage`** is right advice (§1.4d), even though the reasoning is wrong.
- **The frame-pacing and warning-is-a-coarse-backstop points** are accurate and correctly sourced.
- **Change one variable per test.** Yes.

---

## 4. Can a client change bitrate mid-session?

**No.** Not by any mechanism that exists in Apollo, Sunshine, Artemis, or Moonlight.

| Route | Available? | Evidence |
|---|---|---|
| Existing control message | **No** | Full type tables enumerated in §1.1. Nothing bitrate-related in either host; Artemis's fork adds only Apollo's three non-video extensions. |
| RTSP renegotiation | **No** | Five verbs mapped (`OPTIONS`/`DESCRIBE`/`SETUP`/`ANNOUNCE`/`PLAY`). No `SET_PARAMETER`, no re-`ANNOUNCE` handling. Bitrate is read once in `cmd_announce` and handed to the encoder. |
| Client API | **No** | Complete `Li*` surface reviewed. No setter. |
| Host-initiated | **No** | Nothing in the host reconfigures the encoder from network feedback. `LOSS_STATS` logs. `0x5502` is not even parsed. |
| Teardown + relaunch | **Yes, only** | `LiStopConnection` → new `LiStartConnection` with a new `StreamConfiguration.bitrate`. |

**Apollo/Artemis specifically:** I looked for a fork-specific mechanism, since that is our
user's actual host, and there is none. Apollo's extensions are `EXEC_SERVER_CMD` (0x3000),
`SET_CLIPBOARD` (0x3001), `FILE_TRANSFER_NONCE_REQUEST` (0x3002) — none touch video. Apollo's
`max_bitrate` config is a host-side ceiling applied at ANNOUNCE. Apollo's Client Commands are
connect/disconnect hooks; the wiki notes *"The new command will take effect when your client
reconnects."* Adaptive bitrate remains an open, unimplemented request (Apollo #399).

**Closest thing to dynamic behaviour that does exist in Apollo** — worth knowing, but it is
not bitrate and not network-driven. `minimum_fps_target` / `min_fps_factor` lets the effective
encode frame rate fall when content is static:

```cpp
// Apollo src/video.cpp:1944-1949
double minimum_fps_target = (config::video.minimum_fps_target > 0.0)
    ? config::video.minimum_fps_target * 1000
    : std::max(config.encodingFramerate / 5, 10000);
auto max_frametime = std::chrono::nanoseconds(1000ms) * 1000 / minimum_fps_target;
// ...
// "Encode at a minimum FPS to avoid image quality issues with static content"
if (auto img = images->pop(max_frametime)) { ... }
```

Content-adaptive, open-loop, host-configured. Same category as `nvenc_vbv_increase`.

**How visible is a restart?** Full session teardown, new RTSP handshake, new encoder, new
IDR — on the order of one to three seconds of black screen. On Apollo with a virtual display
it also tears down and recreates the display, which can flicker the host's real monitors and
re-trigger the display-mode override path. This is fine as a user-initiated "Apply". It is
categorically unusable as the actuator in an automatic control loop.

### 4.1 What the client *can* legitimately adapt, unilaterally

These need no host cooperation beyond messages the host already handles.

| Lever | Mechanism | Host actually responds? |
|---|---|---|
| **IDR request on unrecoverable loss** | `IDX_REQUEST_IDR_FRAME`, urgent channel | **Yes** — `session->video.idr_events->raise(true)` |
| **Reference-frame invalidation** | `IDX_INVALIDATE_REF_FRAMES` (`0x0301`), `(int64 first, int64 last)` | **Yes** — `invalidate_ref_frames_events->raise(...)`. Cheaper than a full IDR when supported. |
| **Loss stats / FEC status reports** | `0x0201`, `0x5502` | **No.** Logged or ignored. Send for wire compatibility; expect nothing back. |
| **Late-frame dropping** | Discard decode units already past their display deadline instead of decoding them | n/a — client-local |
| **Decoder queue depth cap** | `LiGetPendingVideoFrames()`; drain-and-request-IDR above a threshold | n/a |
| **Frame pacing mode** | Render-on-arrival vs vsync-aligned | n/a |
| **Session-start bitrate selection** | Choose `StreamConfiguration.bitrate` at launch from network class | Host honours it at ANNOUNCE |

The last row is the important one. Artemis already does a coarse version: `Game.java:790`
picks `prefConfig.meteredBitrate` instead of `prefConfig.bitrate` when Android reports a
metered connection, with `meteredBitrate` defaulting to `bitrate / 4`. That is adaptation at
the only point in the session where bitrate is actuable, and it is the right shape.

### 4.2 Signals, and what a non-oscillating control law looks like

Available signals, all already exposed or trivially derived:

| Signal | Source | Tells you |
|---|---|---|
| Frame loss % over a window | `framesLost / totalFrames` per interval | Network loss **or** encoder/decoder overrun — ambiguous |
| Unrecoverable vs FEC-recovered blocks | our RS reassembly path (`01-PROTOCOL.md` §7.7) | Loss severity vs FEC adequacy — disambiguates real congestion |
| Decode time per frame | MediaCodec dequeue→release delta | Decoder is the binding term |
| Host processing latency | Sunshine frame header, per frame | Encoder is the binding term |
| RTT + variance | `LiGetEstimatedRttInfo` | Queueing / bufferbloat |
| Pending video frames | `LiGetPendingVideoFrames` | Client-side backlog |
| Link class | Android `ConnectivityManager` (Ethernet / 5 GHz / 2.4 GHz / metered) | Prior on stability |

**Reference control law — steal Moonlight's, it is already tuned and already ships:**

```
window            = 3000 ms
POOR   if  loss >= 30%                                  (single window)
       or  loss >= 15% for two consecutive windows
OKAY   if  loss <=  5%
```
— [`ControlStream.c:124-127, 486-509`](https://github.com/ClassicOldSong/moonlight-common-c/blob/master/src/ControlStream.c)

Note the shape: **asymmetric thresholds** (30/15 down, 5 up), **a dwell requirement** on the
milder trigger, and **a wide dead band** between 5% and 15% where nothing happens. That dead
band is the anti-oscillation mechanism, and it is why the estimator is stable enough to drive
a user-facing warning.

If we ever actuate, the law must be strictly more conservative than the estimator, because
our actuator costs a visible blackout:

- **Dead band** at least as wide as Moonlight's, ideally wider (down at ≥20% sustained, up at ≤3%).
- **Dwell:** ≥3 consecutive bad windows (≈9 s) before any action. A single bad window is a
  microwave oven, not a trend.
- **Cooldown:** no second action within ≥5 minutes, and a hard cap of ~2 actions per session.
  If a link needs more than two corrections, the static value is wrong and the user should be
  told, not silently ratcheted.
- **Multiplicative decrease, additive increase**, with a floor (never below the resolution's
  default) and a ceiling (the user's setting — never exceed what they asked for).
- **Disambiguate before acting.** Only act on loss that correlates with *unrecoverable FEC
  blocks* and *stable* decode time and *stable* host processing latency. If decode time is
  rising, the decoder is the binding term and lowering bitrate is the wrong fix — cap frame
  rate or drop late frames instead. If host processing latency is rising, the encoder is
  bound and bitrate reduction helps only incidentally.
- **Never act when the link class is Ethernet.** The document's stable-LAN argument is correct
  in that one case, and we can detect it.

### 4.3 Where dynamic bitrate makes things worse

- **Thrash.** An actuator that costs 1–3 s of black screen turns any oscillation into a
  catastrophically worse experience than a mediocre static value. This is the failure GFE
  actually shipped and Moonlight actually disabled.
- **Ratchet-down on a transient.** A lower-only loop plus one microwave oven leaves the user
  at 40% quality for the rest of the evening. Recovery must be as automatic as the reduction.
- **Acting on the wrong binding term.** Frame loss is the sum of network loss, encoder
  overrun, decoder overrun and WiFi contention. Reducing bitrate only addresses the first.
  Reducing it when the decoder is thermally throttled is a pure quality loss with no benefit.
- **Interaction with `nvenc_vbv_increase`.** With VBV relaxed, scene-change bursts produce
  loss that is encoder-scheduled, not congestion. A loss-driven loop will fight the encoder's
  rate controller and lose.
- **A stable wired LAN.** Nothing to gain, everything to lose. Gate on link class.
- **User trust.** Quality changing without an explanation reads as a bug. Any automatic action
  must be visible and reversible.

---

## 5. Corrections required to `01-PROTOCOL.md`

Two errors found while verifying, both in our own spec, both from this research:

1. **§9.5 — "It drives Sunshine's adaptive FEC" is wrong.** Sunshine and Apollo consult
   `mlFeatureFlags` only for `ML_FF_SESSION_ID_V1`; `ML_FF_FEC_STATUS` is never tested and
   `0x5502` has no inbound handler. There is no adaptive FEC. Restate as: *we send it for wire
   compatibility with `x-ml-general.featureFlags` bit `0x1`; current hosts ignore it.*
   (The type value `0x5502` and the big-endian layout **are** correct —
   `SS_FRAME_FEC_PTYPE 0x5502` in [`Video.h`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Video.h).
   Worth a note that `0x5502` collides with Sunshine's outbound "Set RGB LED"; the directions
   differ so there is no wire ambiguity, but a naive bidirectional type switch would break.)

2. **§6.4 — `fec.repairPercent` condition is wrong.** Our spec says `20` on LAN, `5` on WAN.
   `moonlight-common-c` sends it **only to GFE hosts** and keys it on **resolution**: `5` at
   ≥3840×2160, `20` otherwise. Against Sunshine/Apollo the attribute is inert — FEC is a host
   config value we cannot influence.

3. **§6.4 — strengthen the `x-ml-video.configuredBitrateKbps` note.** Currently described as a
   convenience ("lets the host know the *user's* number"). Against Apollo it is **mandatory**:
   omitting it causes a double FEC deduction and a 0.64× encode. See §1.3.

§6.4's core claim — bitrate cannot be changed mid-session, `min == max`, no client→host
bitrate message, "do not add a speculative set-bitrate control message" — is **fully confirmed**
and should stand exactly as written.

---

## 6. Recommendation

**Do not build dynamic bitrate as a closed loop. Build the three pieces that are actually
implementable.**

The reason is mechanical, not philosophical. There is no actuator. The only way to change
bitrate is to restart the stream, and a control loop whose actuator costs a visible blackout
is not a control loop — it is a bug generator. The document reaches the right conclusion for
partly wrong reasons; the right reason is §4.

### Build now

**A. Link-quality estimator + advisory (`media/`)**
Port Moonlight's 3-second-window loss estimator with its 30/15/5 thresholds, and extend it
with the four disambiguating signals from §4.2 (unrecoverable FEC blocks, decode time, host
processing latency, RTT variance). Surface it as: an on-screen indicator during the stream,
and a post-session summary that names the **binding term** — "your decoder is the limit",
"the host encoder is the limit", "the network is the limit". This is the document's best idea
(§1 `min(decoder, encoder, network)`) turned into something the app can say. It is cheap, it
is reusable, and it is a prerequisite for everything else.

**B. Per-network bitrate profiles chosen at launch (`ui/` + `protocol/`)**
Store bitrate (and optionally codec/resolution) per network identity — Ethernet, per-SSID,
metered — and select at `LiStartConnection`. This is exactly the document's §2.2
"operator-adaptive" mechanism, automated at the one moment where bitrate is actuable, and it
is what Artemis already gestures at with `meteredBitrate`. Seed new profiles from the
estimator's recommendation after the first session on that network. No protocol work, no
host cooperation, no restart, no oscillation risk.

**C. Client-side resilience (`media/`)** — already in the pipeline, confirm it is right:
rate-limited IDR requests (≤1 per 100 ms), reference-frame invalidation where supported,
late-frame dropping, and a decoder queue depth cap driven by `LiGetPendingVideoFrames`.

### Defer, gated

**D. Opt-in auto-downgrade with reconnect.** Default **off**. At most one action per session,
≥9 s of sustained corroborated degradation before firing, ≥5 min cooldown, never on Ethernet,
always announced to the user, always one-tap revertible. Ship this only if (A) shows, from
real telemetry, that it would have helped often enough to justify a blackout. Do not build it
speculatively.

### Do not build

- Any continuous or per-frame bitrate control loop.
- Any speculative "set bitrate" control message. It does not exist; a host that receives an
  unknown type drops it silently, so we would ship a feature that appears to work and does nothing.
- Any UI that implies bitrate is live-adjustable. `03-UI-SPEC.md` §5.3's "Reconnect required"
  label is correct and should stay.

### Must-fix regardless

- Send `x-ml-video.configuredBitrateKbps` = **raw user value** in ANNOUNCE (§1.3). Without it,
  Apollo encodes at 0.64× intent.
- Model the bitrate slider as a **total wire budget**, not video-only. Do not add 20% anywhere
  in the UI or in bandwidth-estimate copy.
- Apply the three `01-PROTOCOL.md` corrections in §5.

---

## Appendix — sources

Source code (all `master` unless noted, retrieved 2026-08-12):
[Sunshine `src/rtsp.cpp`](https://github.com/LizardByte/Sunshine/blob/master/src/rtsp.cpp) ·
[`src/stream.cpp`](https://github.com/LizardByte/Sunshine/blob/master/src/stream.cpp) ·
[`src/config.cpp`](https://github.com/LizardByte/Sunshine/blob/master/src/config.cpp) ·
[`src/nvenc/nvenc_base.cpp`](https://github.com/LizardByte/Sunshine/blob/master/src/nvenc/nvenc_base.cpp) ·
[`docs/configuration.md`](https://github.com/LizardByte/Sunshine/blob/master/docs/configuration.md) ·
[Apollo `src/rtsp.cpp`](https://github.com/ClassicOldSong/Apollo/blob/master/src/rtsp.cpp) ·
[`src/stream.cpp`](https://github.com/ClassicOldSong/Apollo/blob/master/src/stream.cpp) ·
[`src/video.cpp`](https://github.com/ClassicOldSong/Apollo/blob/master/src/video.cpp) ·
[`src/config.cpp`](https://github.com/ClassicOldSong/Apollo/blob/master/src/config.cpp) ·
[moonlight-common-c `SdpGenerator.c`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/SdpGenerator.c) ·
[`ControlStream.c`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c) ·
[`Limelight.h`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Limelight.h) ·
[`Video.h`](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/Video.h) ·
[ClassicOldSong/moonlight-common-c (Artemis fork)](https://github.com/ClassicOldSong/moonlight-common-c) ·
[Artemis `moonlight-android` @ `v20.3.0-experimental.9`](https://github.com/ClassicOldSong/moonlight-android) ·
[moonlight-qt `streamingpreferences.cpp`](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp) ·
[`session.cpp`](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/streaming/session.cpp)

Issues, docs, discussions:
[moonlight-qt #802](https://github.com/moonlight-stream/moonlight-qt/issues/802) ·
[#1618](https://github.com/moonlight-stream/moonlight-qt/issues/1618) ·
[#1708](https://github.com/moonlight-stream/moonlight-qt/issues/1708) ·
[Apollo #399](https://github.com/ClassicOldSong/Apollo/issues/399) ·
[Apollo wiki — Best Practices](https://github.com/ClassicOldSong/Apollo/wiki/Best-Practices) ·
[Apollo wiki — Client Commands](https://github.com/ClassicOldSong/Apollo/wiki/Client-Commands) ·
[Apollo wiki — Stuttering Clinic](https://github.com/ClassicOldSong/Apollo/wiki/Stuttering-Clinic) ·
[moonlight-tv #346](https://github.com/mariotaku/moonlight-tv/issues/346) ·
[#518](https://github.com/mariotaku/moonlight-tv/issues/518)

**Unverified items, restated:** the four comments on Apollo #399 (state verified open,
maintainer's reasoning not read); NVIDIA's `NV_ENC_SPLIT_AUTO_MODE` activation conditions;
the HDR bitrate premium; the "120 Mbps knee" at 4K; 2.4 GHz 720p viability.
