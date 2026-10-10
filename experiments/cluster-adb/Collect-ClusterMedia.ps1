<#
Guided, read-only ADB capture of the Android side of a car instrument-cluster issue.
Requires Windows PowerShell 5.1+ or PowerShell 7 and an authorized ADB device.
Does not grant permissions, start apps, control playback, or change device settings.
#>
[CmdletBinding()]
param(
    [string]$Adb,
    [string]$Serial,
    [string]$OutputDirectory,
    [ValidateSet('YMPlayer', 'FMPLAY', 'YandexMusic')][string]$QuickPlayer,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
trap {
    Write-Host ("Ошибка: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)
$script:Players = [ordered]@{
    YMPlayer    = 'dev.petrov.ymplayer2'
    FMPLAY      = 'ru.fmplay'
    YandexMusic = 'ru.yandex.music'
}
$script:PackagePattern = '(?:dev\.petrov\.ymplayer2|ru\.fmplay|ru\.yandex\.music)(?![A-Za-z0-9_.])'

function Write-Utf8([string]$Path, [string]$Content) {
    [IO.File]::WriteAllText($Path, $Content.TrimEnd() + "`n", $script:Utf8)
}

function Resolve-Adb([string]$Requested) {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    if ($Requested) { $candidates.Add($Requested) }
    else {
        $candidates.Add((Join-Path $PSScriptRoot 'adb.exe'))
        if ($env:ANDROID_HOME) { $candidates.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) }
        if ($env:ANDROID_SDK_ROOT) { $candidates.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')) }
        if ($env:LOCALAPPDATA) { $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
        $candidates.Add('adb.exe')
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command -and $command.CommandType -eq 'Application') { return $command.Source }
    }
    throw 'adb.exe не найден. Положите его рядом со скриптом или укажите -Adb C:\путь\к\adb.exe.'
}

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 45) {
    # No shell is involved. All arguments used below are fixed words or validated serials.
    foreach ($argument in $Arguments) {
        if ($argument -match '[\s"]') { throw "Недопустимый аргумент ADB: $argument" }
    }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $script:AdbPath
    $start.Arguments = $Arguments -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    $start.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::Start($start)
    if ($null -eq $process) { throw 'Не удалось запустить adb.exe.' }
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            return [pscustomobject]@{ Code = -1; Text = "ADB не ответил за $TimeoutSeconds с." }
        }
        $text = ($stdout.Result + "`n" + $stderr.Result).Trim()
        return [pscustomobject]@{ Code = $process.ExitCode; Text = $text }
    } finally { $process.Dispose() }
}

function Invoke-Device([string[]]$Arguments, [int]$TimeoutSeconds = 45) {
    Invoke-Adb -Arguments (@('-s', $script:DeviceSerial) + $Arguments) -TimeoutSeconds $TimeoutSeconds
}

function Require-Device([string[]]$Arguments) {
    $result = Invoke-Device -Arguments $Arguments
    if ($result.Code -ne 0) { throw "ADB: $($Arguments -join ' '): $($result.Text)" }
    return $result.Text.Trim()
}

function Select-TargetSessions([string]$Dump) {
    $lines = $Dump -split "`r?`n"
    $selected = New-Object 'System.Collections.Generic.List[string]'
    $count = 0
    for ($index = 0; $index -lt $lines.Length; $index++) {
        $line = $lines[$index]
        if ($line -match '^\s*(?:Global priority session|Media button session|Last MediaButtonReceiver)') {
            if ($line -match $script:PackagePattern -or $line -match '\bnull\b') { $selected.Add($line) }
            else { $selected.Add(($line -replace '(?<=session is |Receiver: ).*$', '<другое приложение>')) }
        }
        if ($line -notmatch "^\s*package=$($script:PackagePattern)\s*$") { continue }
        $indent = ([regex]::Match($line, '^\s*')).Length
        $header = ''
        for ($back = $index - 1; $back -ge 0; $back--) {
            if (-not $lines[$back].Trim()) { continue }
            if (([regex]::Match($lines[$back], '^\s*')).Length -lt $indent) {
                if ($lines[$back] -match $script:PackagePattern) { $header = $lines[$back] }
                break
            }
        }
        $selected.Add('--- target session ---')
        if ($header) { $selected.Add($header) }
        $selected.Add($line)
        $count++
        for ($next = $index + 1; $next -lt $lines.Length; $next++) {
            $entry = $lines[$next]
            if ($entry.Trim() -and ([regex]::Match($entry, '^\s*')).Length -lt $indent) { break }
            $selected.Add($entry)
        }
        $index = $next - 1
    }
    if ($count -eq 0) { $selected.Add('Целевые MediaSession не найдены в выводе dumpsys.'); }
    return [pscustomobject]@{ Text = ($selected -join "`n"); Count = $count }
}

function Select-TargetNotifications([string]$Dump) {
    $lines = $Dump -split "`r?`n"
    $selected = New-Object 'System.Collections.Generic.List[string]'
    $count = 0
    for ($index = 0; $index -lt $lines.Length; $index++) {
        $line = $lines[$index]
        if ($line -notmatch "^\s*NotificationRecord\(.*\bpkg=$($script:PackagePattern)(?:\s|,)") { continue }
        $indent = ([regex]::Match($line, '^\s*')).Length
        $selected.Add('--- target notification ---')
        $selected.Add($line)
        $count++
        for ($next = $index + 1; $next -lt $lines.Length; $next++) {
            $entry = $lines[$next]
            if ($entry.Trim() -and ([regex]::Match($entry, '^\s*')).Length -le $indent) { break }
            $selected.Add($entry)
        }
        $index = $next - 1
    }
    if ($count -eq 0) { $selected.Add('Целевые NotificationRecord не найдены в выводе dumpsys.'); }
    return [pscustomobject]@{ Text = ($selected -join "`n"); Count = $count }
}

function Test-Selectors {
    $sessions = @'
Media button session is dev.petrov.ymplayer2/session/1
  first dev.petrov.ymplayer2/session/1
    package=dev.petrov.ymplayer2
    metadata: title, artist
  unrelated other/session/2
    package=other.private.app
    metadata: PRIVATE_OTHER_APP
'@
    $notifications = @'
    NotificationRecord(1: pkg=dev.petrov.ymplayer2 user=0)
      android.title=My track
    NotificationRecord(2: pkg=other.private.app user=0)
      android.title=PRIVATE_OTHER_APP
'@
    $a = Select-TargetSessions $sessions
    $b = Select-TargetNotifications $notifications
    if ($a.Count -ne 1 -or $b.Count -ne 1 -or
        $a.Text -match 'PRIVATE_OTHER_APP' -or $b.Text -match 'PRIVATE_OTHER_APP' -or
        $a.Text -notmatch 'metadata: title' -or $b.Text -notmatch 'My track') {
        throw 'Внутренняя проверка фильтра не прошла.'
    }
    Write-Host 'Фильтр целевых MediaSession и уведомлений: OK.'
}

if ($SelfTest) { Test-Selectors; return }

if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'reports' }

$script:AdbPath = Resolve-Adb $Adb
$devices = Invoke-Adb -Arguments @('devices', '-l')
if ($devices.Code -ne 0) { throw "ADB devices: $($devices.Text)" }
$available = @($devices.Text -split "`r?`n" | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
})
if ($Serial) {
    if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Недопустимый serial ADB.' }
    if ($available -notcontains $Serial) { throw "Устройство $Serial не готово. Вывод adb devices:`n$($devices.Text)" }
    $script:DeviceSerial = $Serial
} elseif ($available.Count -eq 1) { $script:DeviceSerial = $available[0] }
else { throw "Нужно одно подключённое устройство или параметр -Serial. Вывод adb devices:`n$($devices.Text)" }

if ((Require-Device @('get-state')) -ne 'device') { throw 'ADB не видит устройство в состоянии device.' }
$runName = 'ClusterMedia-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$root = [IO.Path]::GetFullPath($OutputDirectory)
$run = Join-Path $root $runName
[IO.Directory]::CreateDirectory($run) | Out-Null
$summary = [ordered]@{
    tool = 'Collect-ClusterMedia 1.0'
    startedUtc = [DateTime]::UtcNow.ToString('o')
    android = (Require-Device @('shell', 'getprop', 'ro.build.version.release'))
    sdk = (Require-Device @('shell', 'getprop', 'ro.build.version.sdk'))
    manufacturer = (Require-Device @('shell', 'getprop', 'ro.product.manufacturer'))
    model = (Require-Device @('shell', 'getprop', 'ro.product.model'))
    buildDisplayId = (Require-Device @('shell', 'getprop', 'ro.build.display.id'))
    notificationListeners = (Invoke-Device @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')).Text
    deviceSettingsChanged = $false
    packages = [ordered]@{}
    captures = New-Object 'System.Collections.Generic.List[object]'
}
foreach ($name in $script:Players.Keys) {
    $package = $script:Players[$name]
    $path = Invoke-Device @('shell', 'pm', 'path', $package)
    $info = Invoke-Device @('shell', 'dumpsys', 'package', $package)
    $version = @($info.Text -split "`r?`n" | Where-Object { $_ -match '^\s*(?:versionCode|versionName)=' } | Select-Object -First 2)
    $summary.packages[$name] = [ordered]@{ package = $package; installed = ($path.Code -eq 0 -and $path.Text -match '^package:'); version = ($version -join '; ') }
}

function Capture-Step([string]$Player, [string]$Stage, [int]$Index) {
    $name = ('{0:D2}-{1}-{2}' -f $Index, $Player, $Stage)
    $folder = Join-Path $run $name
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $sessionsRaw = Invoke-Device @('shell', 'dumpsys', 'media_session') 60
    $notificationsRaw = Invoke-Device @('shell', 'dumpsys', 'notification', '--noredact') 60
    $notificationMode = '--noredact'
    if ($notificationsRaw.Code -ne 0 -or $notificationsRaw.Text -match '(?i)unknown (?:argument|option).*noredact') {
        $notificationsRaw = Invoke-Device @('shell', 'dumpsys', 'notification') 60
        $notificationMode = 'standard fallback'
    }
    $sessions = if ($sessionsRaw.Code -eq 0) { Select-TargetSessions $sessionsRaw.Text }
        else { [pscustomobject]@{ Text = "Ошибка dumpsys media_session: $($sessionsRaw.Text)"; Count = 0 } }
    $notifications = if ($notificationsRaw.Code -eq 0) { Select-TargetNotifications $notificationsRaw.Text }
        else { [pscustomobject]@{ Text = "Ошибка dumpsys notification: $($notificationsRaw.Text)"; Count = 0 } }
    Write-Utf8 (Join-Path $folder 'media-sessions.txt') $sessions.Text
    Write-Utf8 (Join-Path $folder 'media-notifications.txt') $notifications.Text
    $item = [ordered]@{
        capturedUtc = [DateTime]::UtcNow.ToString('o')
        player = $Player; package = $script:Players[$Player]; stage = $Stage
        clusterObservation = ''
        sessionCount = $sessions.Count; notificationCount = $notifications.Count
        notificationDumpMode = $notificationMode
        mediaSessionExitCode = $sessionsRaw.Code; notificationExitCode = $notificationsRaw.Code
        folder = $name
    }
    Write-Utf8 (Join-Path $folder 'capture.json') ($item | ConvertTo-Json -Depth 4)
    $summary.captures.Add($item)
    Write-Host ("  Снимок {0}: сессий {1}, уведомлений {2}" -f $name, $sessions.Count, $notifications.Count)
    return $item
}

function Record-Observation($Item, [string]$Question) {
    $Item['clusterObservation'] = Read-Host $Question
    Write-Utf8 (Join-Path (Join-Path $run $Item.folder) 'capture.json') ($Item | ConvertTo-Json -Depth 4)
}

Write-Host "ADB: $script:DeviceSerial; Android $($summary.android), $($summary.model)"
Write-Host 'Скрипт ничего не меняет на ГУ. Управляйте плеерами вручную.'
$index = 0
if ($QuickPlayer) {
    $index++
    $item = Capture-Step $QuickPlayer 'quick' $index
    $item['clusterObservation'] = 'not recorded'
    Write-Utf8 (Join-Path (Join-Path $run $item.folder) 'capture.json') ($item | ConvertTo-Json -Depth 4)
} else {
    foreach ($player in $script:Players.Keys) {
        if (-not $summary.packages[$player].installed) {
            Write-Host "$player не установлен; пропускаю."
            continue
        }
        Write-Host "`n=== $player ==="
        $answer = Read-Host 'Остановите другой плеер, запустите этот и дождитесь названия/обложки. Enter = снять, S = пропустить'
        if ($answer -match '^[sSсС]$') { continue }
        $index++; $item = Capture-Step $player 'playing' $index
        Record-Observation $item 'Что показывает приборка? Кратко; пустой ответ = неизвестно'
        $answer = Read-Host 'Смените трек/станцию, дождитесь обновления. Enter = снять, S = пропустить'
        if ($answer -notmatch '^[sSсС]$') {
            $index++; $item = Capture-Step $player 'changed' $index
            Record-Observation $item 'Что показывает приборка после смены?'
        }
        $answer = Read-Host 'Поставьте паузу. Enter = снять, S = пропустить'
        if ($answer -notmatch '^[sSсС]$') {
            $index++; $item = Capture-Step $player 'paused' $index
            Record-Observation $item 'Что показывает приборка на паузе?'
        }
        $answer = Read-Host 'Продолжите воспроизведение. Enter = снять, S = пропустить'
        if ($answer -notmatch '^[sSсС]$') {
            $index++; $item = Capture-Step $player 'resumed' $index
            Record-Observation $item 'Что показывает приборка после продолжения?'
        }
    }
}

$summary['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
Write-Utf8 (Join-Path $run 'summary.json') ($summary | ConvertTo-Json -Depth 8)
Write-Utf8 (Join-Path $run 'README.txt') 'Снимки содержат только блоки media_session/notification трёх целевых плееров и названия треков. Перед отправкой просмотрите файлы. Сырые общесистемные dumpsys и logcat не сохранялись.'
$zip = Join-Path $root ($runName + '.zip')
Compress-Archive -Path (Join-Path $run '*') -DestinationPath $zip -Force
Write-Host "`nГотово: $zip"
Write-Host 'Отправьте ZIP автору для сравнения. Если у всех снимков нули, сообщите об этом вместе с ZIP.'
