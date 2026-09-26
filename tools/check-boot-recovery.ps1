param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'C:\Users\petro\.codex\android-sdk\platform-tools\adb.exe',
    [string]$Output = '.local-build\boot-recovery'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This check is restricted to an explicitly named emulator.' }
$root = Split-Path $PSScriptRoot -Parent
$app = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$tests = Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $app) -or -not (Test-Path -LiteralPath $tests)) {
    throw 'Build :app:assembleDebug :app:assembleDebugAndroidTest first.'
}
$destination = [IO.Path]::GetFullPath((Join-Path $root $Output))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
function Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}
function Phase([string]$Name) {
    $result = Adb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'dev.petrov.ymplayer2.BootCheckpointTest#checkpointAcrossEmulatorBoot',
        '-e', 'bootPhase', $Name,
        'dev.petrov.ymplayer2.dev.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath (Join-Path $destination "$Name.txt") -Encoding UTF8
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Boot checkpoint $Name phase failed. See $destination" }
}
Adb @('install', '-r', $app) | Out-Null
Adb @('install', '-r', $tests) | Out-Null
Phase 'seed'
Adb @('reboot') | Out-Null
& $Adb -s $Serial wait-for-device | Out-Null
$booted = $false
for ($i = 0; $i -lt 120; $i++) {
    if ((& $Adb -s $Serial shell getprop sys.boot_completed 2>$null) -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $booted) { throw 'Emulator did not finish booting within 120 seconds.' }
Phase 'verify'
"Checkpoint survived emulator reboot on $Serial; playback stayed paused."
