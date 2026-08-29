<#
    focus-reporter.ps1 — tells an Artemis client what kind of field has focus.

    The client receives encoded video and nothing else, so it cannot know that
    the box you just clicked only takes digits. Windows does know, because the
    focused control declares an input scope, but that is an in-process API with
    no network-visible surface. This reads it and says so in one small UDP
    datagram, which is the whole of the trick.

    It is meant to be started and stopped by Vibepollo, so it runs only while a
    stream is running. See the header of the README next to this file.

    Nothing is received, no port is opened for listening, and nothing is
    written to disk. It sends, and that is all.
#>

[CmdletBinding()]
param(
    # The handheld's address on your network.
    [Parameter(Mandatory = $true)][string] $ClientAddress,

    # The token shown in Artemis under the keyboard settings.
    [Parameter(Mandatory = $true)][string] $Token,

    [int] $Port = 47996,

    # How often to look. 150 ms is well under the time it takes to click a box
    # and start typing, and costs a fraction of a percent of one core.
    [int] $PollMs = 150,

    # A backstop only, in case the stop command never runs. Not an idle timer:
    # an earlier version exited after five minutes without a focus change,
    # which killed it whenever someone simply stopped clicking about.
    [int] $MaxRuntimeHours = 12
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

# ES_NUMBER on a classic Win32 edit control: the field takes digits only.
$signature = @'
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr hWnd, int nIndex);
'@
$Win32 = Add-Type -MemberDefinition $signature -Name 'FocusWin32' -Namespace 'Vl' -PassThru

$GWL_STYLE = -16
$ES_NUMBER = 0x2000

function Get-FocusState {
    try {
        $element = [System.Windows.Automation.AutomationElement]::FocusedElement
    } catch {
        return 'unknown'
    }
    if ($null -eq $element) { return 'none' }

    try {
        $info = $element.Current
    } catch {
        return 'unknown'
    }

    # Not a text box at all, so nothing wants a keyboard.
    if ($info.ControlType -ne [System.Windows.Automation.ControlType]::Edit -and
        $info.ControlType -ne [System.Windows.Automation.ControlType]::Document) {
        return 'none'
    }

    # A password box is text, not digits, even when people put numbers in it.
    if ($info.IsPassword) { return 'text' }

    # A classic edit control says outright whether it takes digits only.
    try {
        if ($info.NativeWindowHandle -ne 0) {
            $style = $Win32::GetWindowLong([IntPtr]$info.NativeWindowHandle, $GWL_STYLE)
            if (($style -band $ES_NUMBER) -ne 0) { return 'digits' }
            return 'text'
        }
    } catch { }

    # A spinner or slider exposes a numeric range, which is as good as saying
    # it takes numbers.
    try {
        $pattern = $null
        if ($element.TryGetCurrentPattern(
                [System.Windows.Automation.RangeValuePattern]::Pattern, [ref] $pattern)) {
            return 'digits'
        }
    } catch { }

    # Anything else with a caret: something wants typing, but which kind cannot
    # be told from here. Browsers and Electron apps land here, which is why the
    # client treats unknown as "change nothing" rather than guessing.
    return 'unknown'
}

$client = New-Object System.Net.Sockets.UdpClient
try {
    $client.Connect($ClientAddress, $Port)
} catch {
    exit 1
}

$last = ''
$lastSent = [DateTime]::UtcNow
$startedAt = [DateTime]::UtcNow

try {
    while ($true) {
        $state = Get-FocusState

        # Send on change, and otherwise once a second so a client that started
        # late still learns the current state.
        $now = [DateTime]::UtcNow
        $due = ($state -ne $last) -or (($now - $lastSent).TotalMilliseconds -ge 1000)

        if ($due) {
            $payload = [Text.Encoding]::ASCII.GetBytes("VLFOCUS1 $Token $state")
            try {
                [void] $client.Send($payload, $payload.Length)
                $lastSent = $now
            } catch {
                # The handheld went away. Keep trying until the idle timeout.
            }
            $last = $state
        }

        if (($now - $startedAt).TotalHours -ge $MaxRuntimeHours) {
            break
        }

        Start-Sleep -Milliseconds $PollMs
    }
} finally {
    $client.Close()
}
