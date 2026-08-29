# Focus reporter

Tells Artemis what kind of field has focus on the PC, so the second screen can
open the letter keyboard or the number pad by itself.

## Setting it up

Two commands in Vibepollo's **Global Prep Command** (Configuration → General).
Copy the address and token from Artemis under **Settings → Focus reporter
setup**.

**Do**

    wscript "C:\path\to\start-focus-reporter.vbs" "C:\path\to\focus-reporter.ps1" 192.168.1.50 abcdef123456

**Undo**

    powershell -NoProfile -ExecutionPolicy Bypass -File "C:\path\to\stop-focus-reporter.ps1"

## Why the Do command goes through a .vbs

This is the part that broke the first time, and it is worth understanding
rather than copying blindly.

Vibepollo does not fire a Do command and move on. It waits for it, and it
fails the whole launch if the exit code is not zero (`src/process.cpp`):

    child.wait(ec);
    auto ret = child.exit_code();
    if (ret != 0) { return -1; }

The reporter is meant to run for the whole session, so pointing a Do command
straight at it holds the launch open and the stream ends before it starts.

`start-focus-reporter.vbs` spawns the reporter in the background and exits
zero immediately, so Vibepollo carries on. `Run(command, 0, False)` — the `0`
hides the window completely, the `False` means do not wait.

## What it does and does not do

- **Sends only.** No listening port, accepts nothing.
- **Writes nothing to disk.**
- **Needs no elevation.**
- **Completely hidden.** No console flash, no tray icon.
- **Stops when the stream stops**, via the Undo command. A twelve hour
  lifetime backstop covers the case where Undo never runs at all.

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
whatever is on screen alone. So this reduces the manual switching rather than
eliminating it.

## If it does not work

Check the reporter is actually running while a stream is up:

    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like '*focus-reporter.ps1*' }

Nothing listed means the Do command did not spawn it — check the paths in the
`wscript` line. Something listed while the keyboard still does not switch
means the datagrams are not arriving: confirm the address is the handheld's
current IP and the token matches the one Artemis is showing.
