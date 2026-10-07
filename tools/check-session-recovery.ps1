param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('force-stop','reboot')][string]$Transition = 'force-stop',
    [ValidateSet('MUSIC','RADIO','CLIPS')][string[]]$Outputs = @('MUSIC','RADIO','CLIPS'),
    [string[]]$States = @('true','false'),
    [string]$Adb = 'C:\Users\petro\.codex\android-sdk\platform-tools\adb.exe',
    [string]$Output = '.local-build\session-recovery',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an explicitly named emulator is allowed.' }
$root = Split-Path $PSScriptRoot -Parent
$destination = [IO.Path]::GetFullPath((Join-Path $root "$Output\$Serial-$Transition"))
New-Item -ItemType Directory -Force -Path $destination | Out-Null
function Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}
if ($Install) {
    Adb @('install','-r',(Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')) | Out-Null
    Adb @('install','-r',(Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) | Out-Null
}
foreach ($mode in $Outputs) { foreach ($playing in $States) {
    if ($playing -notin @('true','false')) { throw 'States must be true or false.' }
    Adb @('shell','am','force-stop','dev.petrov.ymplayer2.dev') | Out-Null
    foreach ($phase in @('seed','verify')) {
        $result = Adb @('shell','am','instrument','-w','-r','-e','class',
            'dev.petrov.ymplayer2.SessionRecoveryTest#acrossProcessOrBoot',
            '-e','recoveryPhase',$phase,'-e','output',$mode,'-e','playing',$playing,
            'dev.petrov.ymplayer2.dev.test/androidx.test.runner.AndroidJUnitRunner')
        $result | Set-Content -LiteralPath (Join-Path $destination "$mode-$playing-$phase.txt") -Encoding UTF8
        if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Failed $mode/$playing/$phase on $Serial. See $destination" }
        if ($phase -eq 'seed') {
            if ($Transition -eq 'force-stop') {
                Adb @('shell','am','force-stop','dev.petrov.ymplayer2.dev') | Out-Null
            } else {
                Adb @('reboot') | Out-Null
                & $Adb -s $Serial wait-for-device | Out-Null
                $booted = $false
                for ($attempt=0; $attempt -lt 120; $attempt++) {
                    if ((& $Adb -s $Serial shell getprop sys.boot_completed 2>$null) -eq '1') { $booted=$true; break }
                    Start-Sleep -Seconds 1
                }
                if (-not $booted) { throw 'Boot timed out.' }
            }
        }
    }
    Write-Output "$Serial $Transition $mode playing=${playing}: passed"
} }
