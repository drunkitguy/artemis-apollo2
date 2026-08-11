# 00 — Project Overview

**Project codename:** VoidLink Android
**Application ID:** `com.voidlink.android`
**Single Gradle module:** `:app`
**minSdk 26 (Android 8.0) / targetSdk 34 (Android 14) / compileSdk 34**
**Language:** Kotlin only. UI: Jetpack Compose + Material 3.

---

## 1. Independence statement (read this first)

This project is an **independent, clean-room reimplementation written from scratch in Kotlin**.

* It is **not affiliated with, endorsed by, or derived from** VoidLink
  (`The-Fried-Fish/VoidLink-previously-moonlight-zwm`), moonlight-ios-zwm, the Moonlight
  project, NVIDIA, or LizardByte/Sunshine.
* **No source code, resources, artwork, string catalogs, or build files are copied** from
  VoidLink, Moonlight (any platform), or any other GameStream client. VoidLink is used only
  as a *product reference*: we looked at its published screenshots and README to decide
  which features a good client should have and roughly what its screens look like.
* The wire protocol documented in `01-PROTOCOL.md` is derived from **publicly documented,
  independently reverse-engineered descriptions of the NVIDIA GameStream protocol** and from
  reading open-source implementations **as protocol documentation only**. Protocol facts (byte
  layouts, magic numbers, endpoint names) are interoperability facts, not creative expression;
  we re-express them in our own words and implement them independently in Kotlin.
* The names "NVIDIA", "GameStream", "GeForce Experience", "Moonlight", "Sunshine", "Apollo",
  and "VoidLink" are the property of their respective owners and are used here only to
  describe interoperability.
* Anything we ship as user-visible text, icons, and layout must be **our own**. Do not
  reproduce VoidLink's exact string wording where it is distinctive; write our own copy.

If the coder ever feels the urge to paste a block of Java/Objective-C from another client:
don't. Read the spec, write Kotlin.

---

## 2. What we are building

A native Android client that streams games and the desktop from a Windows/Linux/macOS PC
running a GameStream-compatible host (NVIDIA GeForce Experience "GameStream", or the
open-source hosts Sunshine and Apollo), over the local network.

The user journey we must support end-to-end:

1. **Find hosts.** Auto-discover hosts on the LAN via mDNS (`_nvstream._tcp`), plus manual
   entry of an IP address or hostname. Remember hosts across launches.
2. **Pair.** Generate a client identity (self-signed X.509 + private key) once, then complete
   the PIN challenge/response handshake with the host so that later requests can use
   client-certificate TLS.
3. **Browse.** Fetch the host's app list with box art, plus a synthetic "Desktop" entry.
4. **Launch / resume / quit.** Start a new session, resume a running one, or cancel it.
5. **Stream.** Negotiate over RTSP, then receive H.264 / HEVC / AV1 video and Opus audio over
   UDP, decode with `MediaCodec` into a `SurfaceView`, play audio with `AudioTrack`, and send
   input (touch, on-screen controls, physical controllers, keyboard, mouse) back over the
   encrypted control channel.
6. **Configure.** A settings surface with global defaults and **per-host overrides**:
   bitrate, resolution, frame rate, codec preference, HDR, YUV 4:4:4, surround audio, touch
   mode, on-screen widget set, gyro mode, gestures.
7. **Wake.** Wake-on-LAN magic packets for offline-but-known hosts.

## 3. Target device profile

| Property | Value |
|---|---|
| Form factors | Phone (portrait + landscape) and tablet / large screen (primary design target, mirroring the iPad reference) |
| Orientation | Full support; stream view defaults to sensor-landscape but supports portrait streaming |
| Input | Touch, physical game controllers (`InputDevice.SOURCE_GAMEPAD`), USB/BT keyboards and mice, stylus |
| Network | LAN / Wi-Fi first. Remote/WAN streaming is best-effort, not a target for v1 |
| Decoders | Hardware `MediaCodec` H.264 required; HEVC and AV1 opportunistic |

## 4. Explicit non-goals for v1

These are **out of scope**. Do not build them; do not leave stubs that imply they exist.

1. **No host software.** We are a client only. We never implement the server side.
2. **No account system, telemetry, analytics, crash reporting, or network calls to any
   server other than the user's own hosts.** Zero third-party backends.
3. **No cloud/remote-relay streaming, no NAT traversal, no port forwarding helper,
   no GameStream-over-internet pairing flow.** LAN only for v1.
4. **No DRM'd or protected-content workaround.** If the host reports protected content, we
   surface the error and stop.
5. **No custom on-screen-controller layout editor in v1.** We ship fixed widget presets
   (Off / Simple / Full); "Custom" appears in the UI as a disabled-with-explanation option.
   (See `04-ROADMAP.md` — the editor is a post-v1 phase.)
6. **No external-display / second-screen modes.** The iPad reference has Stage Manager and
   AirPlay options; the Android equivalent (presentation displays) is deferred. The UI spec
   documents a "Peripherals" section but its external-display row is **disabled, with the row's inline info text explaining why**, in
   v1.
7. **No macros / command-shortcut editor**, no keyboard-shortcut recorder.
8. **No local-file game library, no emulator integration, no non-GameStream backends
   (Steam Link, Parsec, RDP, VNC).**
9. **No in-app purchases, no paywall, no license checks.**
10. **No Android TV / leanback UI.** Touch-first. (Controller navigation of the launcher UI
    is a nice-to-have, not a v1 gate.)
11. **No IPv6-specific handling beyond what `InetAddress` gives us for free.**
12. **No native (NDK/JNI) code except where unavoidable** — see the Reed-Solomon and Opus
    notes in `04-ROADMAP.md`. Default posture: pure Kotlin/Java + platform APIs.

## 5. Hard product requirements

* **Cold start to host list < 500 ms** on a warm cache; discovery results stream in.
* **Glass-to-glass added latency budget:** decoder queue depth must never exceed 1 frame;
  we always render the newest complete frame. Any code path that buffers "for smoothness"
  is a bug.
* **The app must never block the main thread on network or crypto.** All protocol work is on
  dedicated threads/coroutine dispatchers (see `02-ARCHITECTURE.md`).
* **Pairing state is per-host and persistent**, keyed by the host's `uniqueid`.
* **The private key never leaves the device** and is stored in app-private storage
  (`filesDir`), optionally wrapped by the Android Keystore. It is never logged.
* **Every settings row carries a help affordance** (the circled-i), because the protocol
  exposes many non-obvious knobs.

## 6. Glossary

| Term | Meaning |
|---|---|
| **Host** | The PC running GameStream/Sunshine/Apollo. |
| **GFE** | NVIDIA GeForce Experience — the original, now-discontinued host. |
| **Sunshine / Apollo** | Open-source hosts. Apollo is a Sunshine fork. Both speak the same protocol with extensions. |
| **Gen** | Protocol generation, derived from the host's `appversion` major number. Gen 3/4/5/7 exist in the wild; **Gen 7 is what everything modern reports.** |
| **NVHTTP** | The XML-over-HTTP(S) control API on ports 47989/47984. |
| **RI key** | "Remote input" AES key — a 16-byte key we generate and hand to the host in the `/launch` query string; used to encrypt input packets. |
| **ENet** | A reliable-UDP protocol library used for the control channel on Gen 5+. We must reimplement the subset we need in Kotlin. |
| **Decode unit (DU)** | One fully reassembled, FEC-repaired video frame, ready for `MediaCodec`. |

## 7. Document map

| File | Contents |
|---|---|
| `00-OVERVIEW.md` | This file. Scope, non-goals, independence. |
| `01-PROTOCOL.md` | The wire protocol, byte-level, with sources and UNVERIFIED markers. |
| `02-ARCHITECTURE.md` | Kotlin packages, threading, state machine, layer interfaces. |
| `03-UI-SPEC.md` | Screens, components, design tokens, light/dark palettes. |
| `04-ROADMAP.md` | Phased build order, each phase compiling, plus risks. |

## 8. Rules for the coder

* **`01-PROTOCOL.md` is normative.** If something there is marked **UNVERIFIED**, do not
  guess silently: implement the documented best guess behind a named constant, log loudly
  when that path is taken, and note it in code with `// UNVERIFIED(spec 01 §x.y)`.
* Prefer failing loudly to failing silently. A stream that dies with a clear error beats a
  black screen.
* Every protocol struct gets a Kotlin data class + an explicit `ByteBuffer` reader/writer
  with a unit test that round-trips a known-good hex blob. Byte order mistakes are the
  single most likely source of bugs in this project.
* No reflection-based JSON/XML magic in the protocol layer. Parse explicitly.
