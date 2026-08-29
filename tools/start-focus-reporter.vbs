' Starts the focus reporter and returns immediately.
'
' This exists because of how Vibepollo runs prep commands. It waits for a Do
' command to finish and fails the whole launch if the exit code is not zero:
'
'   child.wait(ec);
'   auto ret = child.exit_code();
'   if (ret != 0) { return -1; }
'
' The reporter is meant to run for the length of the session, so pointing a Do
' command straight at it holds the launch open and the stream never starts.
' This launcher spawns it in the background, exits zero straight away, and
' lets Vibepollo get on with starting the stream.
'
' Run(command, 0, False): 0 hides the window entirely, False means do not wait.
'
' Usage:
'   wscript "start-focus-reporter.vbs" "<path to focus-reporter.ps1>" <client ip> <token>

Option Explicit

Dim args, shell, scriptPath, clientAddress, token, command
Set args = WScript.Arguments

If args.Count < 3 Then
    WScript.Quit 0   ' Never fail the launch over our own arguments.
End If

scriptPath = args(0)
clientAddress = args(1)
token = args(2)

command = "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & _
          scriptPath & """ -ClientAddress " & clientAddress & " -Token " & token

Set shell = CreateObject("WScript.Shell")
On Error Resume Next
shell.Run command, 0, False
On Error GoTo 0

WScript.Quit 0
