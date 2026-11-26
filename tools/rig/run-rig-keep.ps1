# A repo-local copy of the shared rig's run-rig.ps1 that keeps the world between server runs (restart checks).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/rig/run-rig-keep.ps1 -Name <probe> -Jars <a.jar>,<b.jar> -Phases <mode1>,<mode2> [-Config <dir>] [-RigDir <dir>] [-Drop <phase>=<jar name>,...]
# It does what run-rig.ps1 does (same rig folder, same lock file rig\rig.lock with the same 10-minute stale rule and 40-minute
# wait, same fresh world and crash-reports at the start, same pass rules per run) and differs in one thing: the phases run
# one after another on ONE world. The world is wiped once, before the first phase, never between them, so a probe can
# save state in phase 1 and read it back in phase 2. The lock is held across all phases, so another session's run (which wipes
# the world) cannot get in between. The shared run-rig.ps1 stays as it is: this is a copy, not an edit.
# Each phase is the probe's -Mode word (written to rig\probe-mode.txt); every phase must pass.
param(
  [Parameter(Mandatory = $true)][string]$Name,
  [Parameter(Mandatory = $true)][string[]]$Jars,
  [Parameter(Mandatory = $true)][string]$Phases,
  [string]$RigDir = 'D:\Claude\addon-rig',
  [int]$TimeoutSec = 600,
  [string]$Config = '',
  [string]$Pass = 'RESULT .*(PASS|ok)',
  [string]$ErrorMatch = '',
  [string]$Drop = ''
)
$ErrorActionPreference = 'Stop'
$base = $RigDir
$rig = Join-Path $base 'rig'
$jdk = 'D:\Claude\enchanting-addon\tools\jdk21\bin'
if ($Name -notmatch '^[a-z0-9_-]+$') { throw 'Name: lower-case letters, digits, _ and -' }
if (-not $ErrorMatch) { $ErrorMatch = [regex]::Escape($Name) }
$modes = @($Phases.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($modes.Count -lt 1) { throw 'Phases: at least one mode word' }
$lock = Join-Path $rig 'rig.lock'
# another session's run holds the rig: wait for it (up to 40 minutes), then take over a lock nobody refreshed for 10
$waitUntil = (Get-Date).AddMinutes(40)
while ((Test-Path $lock) -and ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes -lt 10) {
  if ((Get-Date) -gt $waitUntil) { throw "the rig is busy (lock $lock) for 40 minutes: one run at a time" }
  Start-Sleep -Seconds 15
}
Set-Content -Encoding ascii $lock "$Name $(Get-Date -Format s)"
$allPassed = $true
try {
  if (-not (Test-Path "$rig\mods")) { New-Item -ItemType Directory "$rig\mods" | Out-Null }
  Get-ChildItem "$rig\mods" -Filter *.jar | Remove-Item -Force
  foreach ($j in @(Get-ChildItem "$base\basemods\*.jar")) { Copy-Item $j.FullName "$rig\mods\" }
  foreach ($j in $Jars) { foreach ($s in $j.Split(',')) { if ($s.Trim()) { Copy-Item $s.Trim() "$rig\mods\" } } }
  # the one wipe: the world and the crash reports, before the first phase only
  foreach ($d in 'world', 'crash-reports') { if (Test-Path "$rig\$d") { Remove-Item "$rig\$d" -Recurse -Force } }
  if (-not (Test-Path "$rig\defaultconfigs")) { New-Item -ItemType Directory "$rig\defaultconfigs" | Out-Null }
  Get-ChildItem "$rig\defaultconfigs" -File | Remove-Item -Force
  if ($Config) {
    foreach ($f in @(Get-ChildItem $Config -Filter *.toml)) {
      Copy-Item $f.FullName "$rig\defaultconfigs\"
      if (Test-Path "$rig\config\$($f.Name)") { Remove-Item "$rig\config\$($f.Name)" -Force }
    }
  }
  "rig (keep world): $Name, phases $($modes -join ', '), mods: $((Get-ChildItem "$rig\mods" -Filter *.jar | ForEach-Object Name) -join ', ')"
  $tag = '\[' + [regex]::Escape($Name) + '\] '
  foreach ($Mode in $modes) {
    $logName = "$Name-$Mode"
    "==== phase '$Mode'"
    # -Drop "<phase>=<jar name>,...": the jar leaves the rig's mods before that phase (a mod taken out of a world that has used it)
    foreach ($d in @($Drop.Split(',') | Where-Object { $_ })) {
      $parts = $d.Split('=', 2)
      if ($parts.Count -eq 2 -and $parts[0].Trim() -eq $Mode) {
        $gone = Join-Path "$rig\mods" $parts[1].Trim()
        if (Test-Path $gone) { Remove-Item $gone -Force; "dropped $($parts[1].Trim()) from the mods before phase '$Mode'" } else { "RIG FAIL: $($parts[1].Trim()) is not in the mods to drop"; $allPassed = $false }
      }
    }
    if (-not $allPassed) { break }
    if (Test-Path "$rig\logs\latest.log") { Remove-Item "$rig\logs\latest.log" -Force }
    Set-Content -Encoding ascii "$rig\probe-mode.txt" $Mode
    $proc = Start-Process -FilePath "$jdk\java.exe" -ArgumentList '@user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.249/win_args.txt nogui' `
      -WorkingDirectory $rig -PassThru -WindowStyle Hidden -RedirectStandardOutput "$rig\stdout.log" -RedirectStandardError "$rig\stderr.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
      Start-Sleep -Seconds 2
      Set-Content -Encoding ascii $lock "$Name $(Get-Date -Format s)"
      if ((Test-Path "$rig\logs\latest.log") -and (Select-String -Path "$rig\logs\latest.log" -Pattern ($tag + 'RESULT') -Quiet)) { break }
    }
    # the server must be fully stopped (world saved, session.lock released) before the next phase starts on the same world
    $stopBy = (Get-Date).AddSeconds(60)
    while (-not $proc.HasExited -and (Get-Date) -lt $stopBy) { Start-Sleep -Seconds 1 }
    $killed = $false
    if (-not $proc.HasExited) { & taskkill /PID $proc.Id /T /F | Out-Null; $killed = $true; Start-Sleep -Seconds 3 }
    if (-not (Test-Path "$rig\logs\latest.log")) { "RIG FAIL: phase '$Mode': the server wrote no log (see rig\stdout.log, rig\stderr.log)"; $allPassed = $false; break }
    New-Item -ItemType Directory -Force "$base\riglogs" | Out-Null
    Copy-Item "$rig\logs\latest.log" "$base\riglogs\$logName-latest.log" -Force
    $lines = Get-Content "$rig\logs\latest.log"
    $mine = @($lines | Where-Object { $_ -match $tag } | ForEach-Object { $_ -replace ('^.*?' + $tag), '' })
    $mine | Select-Object -Last 80
    $errors = @($lines | Where-Object { $_ -match 'ERROR|Exception' -and $_ -match "(?i)$ErrorMatch" })
    "---- ERROR/Exception lines matching '$ErrorMatch': $($errors.Count) (full log: riglogs\$logName-latest.log)"
    $errors | Select-Object -First 10
    if (Test-Path "$rig\crash-reports") { Get-ChildItem "$rig\crash-reports" -File | ForEach-Object { "CRASH REPORT: $($_.Name)" } }
    $passN = @($mine | Where-Object { $_ -match "^$Pass" }).Count
    $failN = @($mine | Where-Object { $_ -match '^CHECK .*: FAIL' -or ($_ -match '^RESULT' -and $_ -notmatch "^$Pass") }).Count
    if ($killed) { 'the server did not stop by itself and was killed' }
    if ($passN -ge 1 -and $failN -eq 0 -and $errors.Count -eq 0) {
      "PHASE PASS: $logName"
    } else {
      "PHASE FAIL: $logName (pass lines $passN, fail lines $failN, error lines $($errors.Count))"
      $allPassed = $false
      break
    }
  }
  if ($allPassed) { "RIG PASS: $Name ($($modes -join ' then '))"; exit 0 }
  "RIG FAIL: $Name"
  exit 1
} finally {
  Remove-Item $lock -Force -ErrorAction SilentlyContinue
}
