# Register (or update) the Vector scheduled jobs in Windows Task Scheduler.
#
# This script is PROVIDED BUT NOT AUTO-RUN by the build session. Execute it yourself when you
# want a persistent daily cadence:
#   powershell -ExecutionPolicy Bypass -File scripts/register-task-scheduler.ps1
#
# Two daily tasks, both of which write a report under reports/ and never exit silently:
#
#   VectorCI-Daily         03:00  the CI gate           (scripts/run-scheduled-ci.mjs)
#   VectorEvolution-Daily  05:00  the learning cycle    (scripts/run-scheduled-evolution.mjs)
#
# The two-hour gap is deliberate, not decorative. Both touch the tile tree and the same
# Docker daemon, and a tile re-bake racing a gate run is one corrupted tile away from a bad
# morning. A full CI gate takes roughly 90 minutes on this host, so 05:00 leaves margin;
# widen it if the gate grows. The evolution wrapper also takes a lock, so even if the gate
# overran badly the cycle would decline to start rather than interleave.

$ErrorActionPreference = 'Stop'

$Workspace  = (Resolve-Path $PSScriptRoot\..).Path          # workspace root (parent of scripts/)
$NodeExe    = (Get-Command node -ErrorAction Stop).Source

$Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

function Register-VectorTask {
    param(
        [Parameter(Mandatory)] [string] $TaskName,
        [Parameter(Mandatory)] [string] $ScriptRelPath,
        [Parameter(Mandatory)] [string] $StartTime,
        [Parameter(Mandatory)] [string] $What
    )
    $Runner = Join-Path $Workspace $ScriptRelPath
    if (-not (Test-Path $Runner)) { throw "runner not found: $Runner" }

    # The action must invoke node directly (not npm) — PowerShell blocks npm scripts in this env.
    $Action  = New-ScheduledTaskAction -Execute $NodeExe -Argument $Runner -WorkingDirectory $Workspace
    $Trigger = New-ScheduledTaskTrigger -Daily -At $StartTime

    Write-Host "Registering '$TaskName' (daily $StartTime) - $What"
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings -Force | Out-Null
}

Register-VectorTask -TaskName 'VectorCI-Daily' `
    -ScriptRelPath 'scripts\run-scheduled-ci.mjs' -StartTime '03:00' `
    -What 'CI gate across every registered repo'

Register-VectorTask -TaskName 'VectorEvolution-Daily' `
    -ScriptRelPath 'scripts\run-scheduled-evolution.mjs' -StartTime '05:00' `
    -What 'learning cycle: vacuum, aggregate, promote, re-bake, metrics, dashboard'

Write-Host ""
Write-Host "Done. Manage with:"
Write-Host "  schtasks /Query /TN VectorCI-Daily"
Write-Host "  schtasks /Query /TN VectorEvolution-Daily"
Write-Host "  schtasks /Run   /TN VectorEvolution-Daily     # fire once, now"
Write-Host "  schtasks /Delete /TN <name> /F"
Write-Host ""
Write-Host "Reports:  reports/latest.md (CI)   reports/evolution-latest.md (cycle)"
Write-Host "Both carry a STALE banner if their schedule stops producing output."
