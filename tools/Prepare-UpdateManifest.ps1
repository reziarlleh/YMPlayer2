param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Notes
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$release = Join-Path $root "releases\$Version"
$apkName = "YMPlayer-$Version.apk"
$apk = Join-Path $release $apkName
$metadata = Get-Content -LiteralPath (Join-Path $release 'build.json') -Raw | ConvertFrom-Json
if ($metadata.versionName -ne $Version -or $metadata.applicationId -ne 'dev.petrov.ymplayer2') {
    throw 'Release metadata does not match the 2.x product.'
}
if (-not (Test-Path -LiteralPath $apk)) { throw 'Signed release APK is missing.' }
$taggedApk = "releases/$Version/$apkName"
$inTag = ([string](& git -C $root ls-tree -r --name-only "v$Version" -- $taggedApk)).Trim()
if ($LASTEXITCODE -ne 0 -or $inTag -ne $taggedApk) {
    throw 'The tagged source does not contain the APK required by the version-pinned CDN URL. Use git add -f for the ignored APK, then tag and push the resulting commit.'
}
$file = Get-Item -LiteralPath $apk
if ($file.Length -gt 20MB) { throw 'The APK exceeds the jsDelivr GitHub-file limit of 20 MB.' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $metadata.sha256) { throw 'Signed release APK differs from build.json.' }
$manifest = [ordered]@{
    schemaVersion = 1
    packageName = 'dev.petrov.ymplayer2'
    versionCode = [int]$metadata.versionCode
    versionName = $Version
    channel = $metadata.channel
    minSdk = 29
    publishedAt = [DateTime]::UtcNow.ToString('o')
    releasePageUrl = "https://github.com/reziarlleh/YMPlayer2/releases/tag/v$Version"
    releaseNotes = $Notes
    apk = [ordered]@{
        primaryUrl = "https://github.com/reziarlleh/YMPlayer2/releases/download/v$Version/$apkName"
        alternativeUrl = "https://cdn.jsdelivr.net/gh/reziarlleh/YMPlayer2@v$Version/releases/$Version/$apkName"
        sizeBytes = $file.Length
        sha256 = $hash
    }
}
$target = Join-Path $root 'update\manifest.json'
New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $target -Encoding UTF8
Write-Output "Prepared $target with primary GitHub and version-pinned jsDelivr APK URLs."
if ($metadata.channel -eq 'stable') {
    $stable = Join-Path $root 'update\stable.json'
    Copy-Item -LiteralPath $target -Destination $stable -Force
    Write-Output "Prepared $stable for stable-channel installations."
}
