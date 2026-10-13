param([string]$Apk, [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $ProjectRoot '.provider/yandex.properties'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Release OAuth configuration is missing: .provider/yandex.properties.' }
$config = ConvertFrom-StringData (Get-Content -LiteralPath $configPath -Raw)
if ([string]::IsNullOrWhiteSpace($config.clientId)) { throw 'Release OAuth clientId is empty; fresh Yandex sign-in would be unavailable.' }
foreach ($key in @('clientId', 'clientSecret')) {
    if ([string]$config[$key] -notmatch '^[A-Za-z0-9_.-]*$') { throw "Invalid OAuth configuration format: $key." }
}
if ($Apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Apk))
    try {
        $found = @{ clientId = $false; clientSecret = [string]::IsNullOrEmpty($config.clientSecret) }
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $stream = $entry.Open()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
            try { $dex = $reader.ReadToEnd() } finally { $reader.Dispose() }
            foreach ($key in @('clientId', 'clientSecret')) {
                if ($config[$key] -and $dex.Contains([string]$config[$key])) { $found[$key] = $true }
            }
        }
        if (-not $found.clientId -or -not $found.clientSecret) { throw 'APK OAuth configuration does not match the release configuration.' }
    } finally { $archive.Dispose() }
}
# Do not print client values, device codes or tokens.
Write-Output 'Release OAuth configuration verified.'
