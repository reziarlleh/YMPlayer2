<#
Read-only comparison of Android media publication. Run once while YMPlayer plays
and once while FMPLAY plays. Requires the owner's authorized ADB connection.
Does not launch/stop players, grant permissions, or change device settings.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][ValidateSet('YMPlayer', 'FMPLAY')][string]$Player,
    [string]$Adb = 'adb',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../.local-build/media-bridge-device')
)
$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
$utf8 = [Text.UTF8Encoding]::new($false)
function Read-Adb([string[]]$Arguments) {
    $lines = @(& $adbPath -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($lines -join "`n")" }
    return ($lines -join "`n")
}
function Save-Text([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $folder $Name), $Text.TrimEnd()+"`n", $utf8)
}
if ((Read-Adb -Arguments @('get-state')).Trim() -ne 'device') { throw 'The selected ADB device is not ready.' }
$folder = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "$Player-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
[IO.Directory]::CreateDirectory($folder) | Out-Null
$packages = @('dev.petrov.ymplayer2', 'ru.fmplay')
$packagePattern = '(?:dev\.petrov\.ymplayer2|ru\.fmplay)(?![A-Za-z0-9_.])'
$header = [ordered]@{
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    scenario = $Player
    serial = $Serial
    sdk = (Read-Adb -Arguments @('shell','getprop','ro.build.version.sdk')).Trim()
    android = (Read-Adb -Arguments @('shell','getprop','ro.build.version.release')).Trim()
    notificationListeners = (Read-Adb -Arguments @('shell','settings','get','secure','enabled_notification_listeners')).Trim()
    deviceSettingsChanged = $false
    note = 'Two allowed player packages only; this snapshot does not prove how the instrument cluster reads Android.'
}
Save-Text 'environment.json' ($header | ConvertTo-Json -Depth 4)

# Keep system routing headers and each target session's fields. Do not save
# metadata/queues from unrelated applications or a full system dump.
$sessions = (Read-Adb -Arguments @('shell','dumpsys','media_session')) -split "`r?`n"
$selected = [Collections.Generic.List[string]]::new()
for ($index=0; $index -lt $sessions.Count; $index++) {
    $line = $sessions[$index]
    if ($line -match '^\s*(Media button session|Global priority session)') { $selected.Add($line) }
    if ($line -notmatch "^\s*package=$packagePattern\s*$") { continue }
    $indent = ([regex]::Match($line, '^\s*')).Length
    $selected.Add("--- target session ---")
    $selected.Add($line)
    for ($next=$index+1; $next -lt $sessions.Count; $next++) {
        $entry = $sessions[$next]
        if ($entry.Trim() -and ([regex]::Match($entry,'^\s*')).Length -lt $indent) { break }
        $selected.Add($entry)
    }
}
if ($selected.Count -eq 0) { $selected.Add('No target session or recognized routing header found.'); }
Save-Text 'media-sessions.txt' ($selected -join "`n")

# --noredact permits reading our player text. Only target NotificationRecord
# blocks are retained; other apps' notifications and global history are discarded.
$notifications = (Read-Adb -Arguments @('shell','dumpsys','notification','--noredact')) -split "`r?`n"
$selected = [Collections.Generic.List[string]]::new()
for ($index=0; $index -lt $notifications.Count; $index++) {
    $line = $notifications[$index]
    if ($line -notmatch "^\s*NotificationRecord\(.*\bpkg=$packagePattern(?:\s|,)") { continue }
    $indent = ([regex]::Match($line,'^\s*')).Length
    $selected.Add($line)
    for ($next=$index+1; $next -lt $notifications.Count; $next++) {
        $entry = $notifications[$next]
        if ($entry.Trim() -and ([regex]::Match($entry,'^\s*')).Length -le $indent) { break }
        $selected.Add($entry)
    }
}
if ($selected.Count -eq 0) { $selected.Add('No target notification record found.'); }
Save-Text 'media-notifications.txt' ($selected -join "`n")
foreach ($package in $packages) {
    $info = (Read-Adb -Arguments @('shell','dumpsys','package',$package)) -split "`r?`n"
    Save-Text "$package-version.txt" (($info | Where-Object { $_ -match '^\s*(versionCode=|versionName=)' }) -join "`n")
}
Write-Output "Saved read-only media snapshot: $folder"
