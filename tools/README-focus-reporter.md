# Focus reporter

Tells Artemis what kind of field has focus on the PC, so the second screen can
open the letter keyboard or the number pad by itself instead of you switching
between them.

Three files, kept together in one folder:

| File | What it is |
| --- | --- |
| `focus-reporter.ps1` | The reporter itself |
| `start-focus-reporter.vbs` | Starts it with no window flash |
| `install-focus-reporter.ps1` | Sets it to run at logon |

## Setting it up

Open **Settings → Focus reporter setup** in Artemis for the address and token,
then run this once on the PC, from the folder the files are in:

    powershell -ExecutionPolicy Bypass -File .\install-focus-reporter.ps1 -ClientAddress 192.168.1.50 -Token abcdef123456

That writes a shortcut into your Startup folder and starts the reporter
immediately, so there is nothing to sign out for. Run the same command again
if the handheld's IP changes.

To remove it:

    powershell -ExecutionPolicy Bypass -File .\install-focus-reporter.ps1 -Uninstall

## It has nothing to do with Vibepollo

Earlier versions of this ran as a Vibepollo prep command. That was a mistake,
and it is worth saying why so nobody puts it back.

Vibepollo does not fire a prep command and move on. It waits for it, and it
fails the whole launch if the exit code is not zero (`src/process.cpp`):

    child.wait(ec);
    auto ret = child.exit_code();
    if (ret != 0) { return -1; }

So anything wrong with the command — a bad path, a script that keeps running,
a missing file — stops the stream from starting at all, with an RTSP handshake
timeout and no hint as to why. A keyboard convenience must not be able to do
that.

The reporter is therefore completely independent. It starts with Windows, sits
idle, and gates itself: every three seconds it checks for an established
connection on the RTSP port (48010) and does nothing more until it finds one.
While nothing is streaming it does not read the UI tree and sends no packets.
If it were deleted mid-session, streaming would carry on unaffected.

**If you previously added the Do and Undo prep commands in Vibepollo, remove
them.** They point at scripts that no longer exist in this repo, and Vibepollo
will fail every launch trying to run them.

## What it does and does not do

- **Sends only.** No listening port, accepts nothing.
- **Writes nothing to disk.**
- **Needs no elevation.**
- **Completely hidden.** No console window, no tray icon.
- **Costs nothing when idle.** One cheap connection lookup every three
  seconds; the UI tree is only read while a stream is up.

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
eliminating it everywhere.

## If it does not work

Check it is running:

    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like '*focus-reporter.ps1*' }

Nothing listed means the shortcut did not start it — re-run the installer.
Something listed while the keyboard still does not switch means the datagrams
are not arriving: confirm the address is the handheld's current IP, that the
token matches the one Artemis is showing, and that UDP 47996 outbound is not
blocked.
