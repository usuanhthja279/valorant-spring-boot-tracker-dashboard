$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this registration script from an elevated PowerShell window.'
}

$taskName = 'Valorant Live Tracker - Start If Stopped'
$startScript = Join-Path $PSScriptRoot 'Start-ValorantTrackerIfStopped.ps1'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$powershell = Join-Path $PSHOME 'powershell.exe'

$action = New-ScheduledTaskAction `
    -Execute $powershell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$startScript`"" `
    -WorkingDirectory $projectRoot

$triggers = @(
    New-ScheduledTaskTrigger -AtStartup
    New-ScheduledTaskTrigger -Daily -At '6:00 AM'
)

$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::FromMinutes(2))

$taskPrincipal = New-ScheduledTaskPrincipal `
    -UserId 'SYSTEM' `
    -LogonType ServiceAccount `
    -RunLevel Highest

$task = New-ScheduledTask `
    -Action $action `
    -Trigger $triggers `
    -Settings $settings `
    -Principal $taskPrincipal `
    -Description 'Starts the VALORANT live tracker at Windows startup and daily at 6 AM only when port 8080 is free.'

Register-ScheduledTask -TaskName $taskName -InputObject $task -Force | Out-Null
Write-Output "Registered '$taskName' for Windows startup and daily at 6:00 AM, running as SYSTEM with highest privileges."
