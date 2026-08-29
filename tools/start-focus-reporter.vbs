' Starts the focus reporter hidden, with no console window, and returns.
'
' PowerShell always flashes a window for a moment when it starts, even with
' -WindowStyle Hidden. Running it through wscript avoids that entirely, which
' matters because this is launched at logon and would otherwise blink a black
' box across the desktop every time you sign in.
'
' Run(command, 0, False): 0 hides the window, False means do not wait.
'
' Usage:
'   wscript "start-focus-reporter.vbs" "<path to focus-reporter.ps1>" <client ip> <token>
'
' install-focus-reporter.ps1 writes a Startup shortcut that calls this, so
' normally you never type it yourself.

Option Explicit

' Nothing here is allowed to raise. This is not defensive habit: it is the one
' hard rule of this whole feature, that a convenience on the handheld can never
' interfere with the PC or with a stream.
On Error Resume Next

Dim args, shell, scriptPath, clientAddress, token, command
Set args = WScript.Arguments

If args.Count < 3 Then
    WScript.Quit 0
End If

scriptPath = args(0)
clientAddress = args(1)
token = args(2)

command = "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & _
          scriptPath & """ -ClientAddress " & clientAddress & " -Token " & token

Set shell = CreateObject("WScript.Shell")
shell.Run command, 0, False

WScript.Quit 0
