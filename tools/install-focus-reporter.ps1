<#
    install-focus-reporter.ps1 — sets the focus reporter to start with Windows.

    Writes a shortcut into your Startup folder and starts the reporter now, so
    you do not have to sign out. Run it again with different arguments to
    update the address or token; run it with -Uninstall to remove it.

    Nothing here touches Vibepollo. The reporter is deliberately independent of
    it: a prep command that fails or hangs stops a stream from starting, and a
    keyboard convenience has no business being able to do that.

    Usage:
        powershell -ExecutionPolicy Bypass -File .\install-focus-reporter.ps1 `
            -ClientAddress 192.168.1.50 -Token abcdef123456

        powershell -ExecutionPolicy Bypass -File .\install-focus-reporter.ps1 -Uninstall
#>

[CmdletBinding(DefaultParameterSetName = 'Install')]
param(
    # The handheld's address, as shown in Artemis under Focus reporter setup.
    [Parameter(Mandatory = $true, ParameterSetName = 'Install')][string] $ClientAddress,

    # The token from the same screen.
    [Parameter(Mandatory = $true, ParameterSetName = 'Install')][string] $Token,

    [Parameter(Mandatory = $true, ParameterSetName = 'Uninstall')][switch] $Uninstall
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$reporter = Join-Path $here 'focus-reporter.ps1'
$launcher = Join-Path $here 'start-focus-reporter.vbs'
$startup = [Environment]::GetFolderPath('Startup')
$shortcut = Join-Path $startup 'Artemis focus reporter.lnk'

function Stop-Reporter {
    # Match on the script path so this cannot pick off an unrelated PowerShell.
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like '*focus-reporter.ps1*' -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Uninstall) {
    if (Test-Path $shortcut) {
        Remove-Item $shortcut -Force
        Write-Host "Removed $shortcut"
    } else {
        Write-Host "Nothing installed at $shortcut"
    }
    Stop-Reporter
    Write-Host 'Stopped any running reporter. Done.'
    exit 0
}

foreach ($path in @($reporter, $launcher)) {
    if (-not (Test-Path $path)) {
        throw "Missing $path. Keep all three files in the same folder."
    }
}

$arguments = '"{0}" {1} {2}' -f $reporter, $ClientAddress, $Token

$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
$link.Arguments = '"{0}" {1}' -f $launcher, $arguments
$link.WorkingDirectory = $here
$link.Description = 'Reports the focused field type to Artemis while streaming'
$link.Save()

Write-Host "Installed $shortcut"

# Replace anything already running so the new address and token take effect.
Stop-Reporter
Start-Process -FilePath (Join-Path $env:WINDIR 'System32\wscript.exe') `
    -ArgumentList ('"{0}" {1}' -f $launcher, $arguments) -WindowStyle Hidden

Write-Host "Reporter running, sending to ${ClientAddress}:47996 when a stream is up."
Write-Host 'It idles at effectively zero cost when nothing is streaming.'
