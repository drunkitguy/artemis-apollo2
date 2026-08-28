# Focus reporter

Tells Artemis what kind of field has focus on the PC, so the second screen can
open the letter keyboard or the number pad by itself.

## Why it has to run on the PC

The client receives encoded video and audio. The kind of field under the cursor
is not in the pixels in any form a machine can read. Windows knows, because the
focused control declares a Text Services input scope, but that is an
in-process API on the host with no network-visible surface — the same reason
you cannot tell what font a document uses by watching a video of it.

So something on the PC has to look and say so. That is all this is: about a
hundred lines of PowerShell that reads the focused element and sends one small
datagram when the answer changes.

## What it does and does not do

- **Sends only.** It opens no listening port and accepts nothing.
- **Writes nothing to disk.**
- **Needs no elevation.**
- **Stops on its own** if the client is unreachable for five minutes, so a
  crashed host that never runs the stop command does not leave it running.

## Running it only while streaming

Vibepollo has a **Global Prep Command**, a Do/Undo pair it runs around every
stream. Put the reporter there and it exists only for the length of a session.

In the Vibepollo web UI, under **Configuration → General → Global Prep
Commands**, add one entry:

**Do**

    powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\path\to\focus-reporter.ps1" -ClientAddress 192.168.1.50 -Token abcdef123456

**Undo**

    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | Where-Object { $_.CommandLine -like '*focus-reporter.ps1*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"

Replace the path, the handheld's address, and the token shown in Artemis under
the keyboard settings.

`-WindowStyle Hidden` keeps it silent. If you still see a console flash on
stream start, wrap the Do command in a one-line VBScript using
`WScript.Shell.Run(cmd, 0, False)`, which suppresses it completely.

## What it can and cannot see

| Where you type | Detected |
| --- | --- |
| Native Win32 dialogs and apps | Yes, including digits-only fields |
| WPF and UWP with an input scope | Yes |
| Password boxes | Yes, reported as text |
| Spinners and numeric steppers | Yes, as digits |
| Browsers and Electron apps | Usually **unknown** |
| Windows lock screen | **No** |

The lock screen runs on a separate secure desktop that no ordinary program can
query, so the PIN box there cannot be detected by this or by anything else
running as you.

`unknown` deliberately changes nothing on the client rather than guessing. A
wrong guess would close the keyboard mid-sentence, which is worse than leaving
whatever is on screen alone.

So this reduces the manual switching rather than eliminating it. In a browser
you will still pick the keyboard yourself.
