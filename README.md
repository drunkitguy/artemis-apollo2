# VoidLink for Android

A native Android game-streaming client for [Sunshine](https://github.com/LizardByte/Sunshine)
and NVIDIA GameStream hosts, written from scratch in Kotlin and Jetpack Compose.

The UI is modelled on **VoidLink** for iOS — the calm, rounded, card-based host and app
browser and the collapsible settings sidebar — brought to Android idioms.

> **This is an independent, from-scratch reimplementation.** It is not affiliated with,
> endorsed by, or derived from the VoidLink, Moonlight, or Artemis projects, and shares no
> code with them. The streaming protocol is implemented here from published protocol
> documentation.

## Status

Early development. **It does not stream video yet.** Being precise about that:

| Area | State |
| --- | --- |
| Host discovery (mDNS) and manual host entry | Implemented |
| Host reachability probing, online/offline/checking | Implemented |
| Pairing with PIN (five-phase handshake, pinned TLS, client certificate) | Implemented |
| App list and box art | Implemented |
| Launch / resume / quit requests | Implemented |
| Wake-on-LAN | Implemented |
| Hosts, app grid and settings UI | Implemented |
| Per-host setting overrides and favourites | Implemented |
| RTSP negotiation, ENet control, RTP video/audio, FEC | **Not built** |
| Video decode, audio playback, input forwarding | **Not built** |

Everything above the line is written from scratch against the protocol
specification in [`docs/01-PROTOCOL.md`](docs/01-PROTOCOL.md) and covered by unit tests,
but none of it has been run against a real host from this project's build environment — see
the verification note in [`docs/04-ROADMAP.md`](docs/04-ROADMAP.md), which labels every
milestone as CI-verifiable or user-verifiable.

Assumptions taken from areas of the protocol that could not be verified are collected in
`UnverifiedProtocolConstants` and logged once per process, so a single run against real
hardware produces a checklist of what to confirm.

## Building

Requires JDK 17 and the Android SDK (compileSdk 34).

```bash
./gradlew assembleDebug     # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease   # release APK -> app/build/outputs/apk/release/
./gradlew testDebugUnitTest # unit tests
```

To sign a release build locally, set `VOIDLINK_KEYSTORE`, `VOIDLINK_KEYSTORE_PASSWORD`,
`VOIDLINK_KEY_ALIAS` and `VOIDLINK_KEY_PASSWORD` in the environment. Without them the
release build is simply left unsigned rather than failing.

## Downloading an APK

Every push builds debug and release APKs in CI and attaches them to a GitHub Release —
see the [Releases](../../releases) page, or grab the `voidlink-apks` artifact from a
[workflow run](../../actions).

The **debug** APK is signed with the standard Android debug key. The **release** APK is
signed with a throwaway key generated during the CI run, so installing a newer release
build over an older one requires uninstalling first.

## Requirements

- Android 8.0 (API 26) or newer
- A host running Sunshine or NVIDIA GameStream on the same network

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/00-OVERVIEW.md`](docs/00-OVERVIEW.md) | Goals, non-goals, and provenance |
| [`docs/01-PROTOCOL.md`](docs/01-PROTOCOL.md) | Pairing, control, video and audio protocol |
| [`docs/02-ARCHITECTURE.md`](docs/02-ARCHITECTURE.md) | Package layout, threading, state machine |
| [`docs/03-UI-SPEC.md`](docs/03-UI-SPEC.md) | Screen and component specification |
| [`docs/04-ROADMAP.md`](docs/04-ROADMAP.md) | Implementation phases and known gaps |

## Licence

MIT. See [`LICENSE`](LICENSE).
