# Build 80 rollback

Everything that could plausibly stop a stream from connecting has been taken
back out. This is what went, what stayed, and the one test that shows where the
fault actually is.

[Download build 80](https://github.com/drunkitguy/artemis-apollo2/releases/download/v20.2.6-b80/artemis-20.2.6-b80-arm64.apk)

## Removed

Deleted from the repository, not hidden behind a toggle. Nothing in this fork
runs on the PC any more.

| | |
| --- | --- |
| PC-side field-type detection — listener, policy, token | deleted |
| `focus-reporter.ps1` and its two launcher scripts | deleted |
| "Switch keyboards from the PC" setting and setup dialog | deleted |
| `Native` resolution option | removed |

**Why Native went too.** Until build 76 it was quietly broken and always fell
through to 720p. Build 76 was the first build that actually asked the host for
whatever mode the panel reported, which is when the failures started. Anything
stored as `Native` is rewritten to 1920×1080 on read, so the setting needs no
attention.

## Kept

All of this starts only after a connection exists, so none of it can affect one.

| | |
| --- | --- |
| Second-screen keyboard and PIN pad, picked by hand | unchanged |
| Trackpad on the second screen | unchanged |
| Metrics banner — resolution, fps, decode latency | unchanged |
| Settings sweep and bitrate test | unchanged |

## What to do

1. **Leave your prep commands alone.** An AutoHotkey activate/deactivate pair
   in Vibepollo has nothing to do with this fork. Earlier advice to remove prep
   commands was wrong — the reporter was never configured there. The one thing
   worth deleting is any step with an **empty Do Command**, since Vibepollo
   aborts a launch on a step that does not exit cleanly and a blank command has
   nothing to exit cleanly.

2. **Delete the Startup shortcut, if you made one.** Only applies if the
   installer was run before it was removed: `Win + R` → `shell:startup` →
   delete **Artemis focus reporter**. This is manual because the uninstaller
   was deleted along with everything else.

3. **Install build 80 and try to stream.** It updates in place over build 78
   and keeps existing settings. Resolution reads 1920×1080 by itself if it was
   on Native.

## What the result means

This build exists to split the problem in half. Either answer is useful.

- **It connects** — the cause was in what came out, most likely the Native
  resolution change asking the host for a mode it could not produce. Anything
  re-added later gets tested one piece at a time.

- **Still error 110** — it was never this client. Next test is stock Artemis or
  Moonlight from the Play Store; it installs alongside and needs no
  configuration. If stock also fails, the fault is on the PC or the network.

Error 110 means the TCP connection to port 48010 timed out: Vibepollo accepted
the launch request but the RTSP stream never became reachable.
