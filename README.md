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

Early development. See [`docs/04-ROADMAP.md`](docs/04-ROADMAP.md) for what works today and
what is still missing — that document is kept honest about which pieces are incomplete.

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
