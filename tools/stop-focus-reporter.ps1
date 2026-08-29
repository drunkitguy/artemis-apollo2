# Stops the focus reporter. Used as the Undo half of the prep command.
#
# Always exits zero. Vibepollo checks the exit code of a prep command and
# treats a non-zero result as a failure, and "there was nothing to stop" is not
# something that should surface as a session error.

$ErrorActionPreference = 'SilentlyContinue'

# Excluding this process and its own filename is not paranoia: the first
# version matched *focus-reporter.ps1*, which also matches
# stop-focus-reporter.ps1, so the stop script killed itself partway through
# and whether it got the reporter first was down to enumeration order.
$self = $PID

Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object {
        $_.ProcessId -ne $self -and
        $_.CommandLine -like '*focus-reporter.ps1*' -and
        $_.CommandLine -notlike '*stop-focus-reporter.ps1*'
    } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

exit 0
