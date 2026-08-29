# Stops the focus reporter. Used as the Undo half of the prep command.
#
# Always exits zero. Vibepollo checks the exit code of these commands, and a
# non-zero result from "there was nothing to stop" is not worth surfacing as a
# session error.

$ErrorActionPreference = 'SilentlyContinue'

Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object { $_.CommandLine -like '*focus-reporter.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

exit 0
