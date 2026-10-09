[CmdletBinding()]
param([string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
& (Join-Path $repo 'gradlew.bat') -p $PSScriptRoot assembleRelease lintRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Media Monitor build/lint failed.' }
$source = Join-Path $PSScriptRoot 'build/outputs/apk/release/MediaMonitor-release.apk'
$destination = Join-Path $repo 'releases/media-monitor/1.0.0beta-build1'
$artifact = Join-Path $destination 'MediaMonitor-1.0.0beta-build1.apk'
$signer = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $signer) { throw 'apksigner not found.' }
$verification = @(& $signer verify --verbose --print-certs $source 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certificate = 'fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec'
if (($verification -join "`n") -notmatch "certificate SHA-256 digest: $certificate") { throw 'Unexpected signing certificate.' }
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if (Test-Path -LiteralPath $artifact) {
    if ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw 'An immutable helper artifact already exists. Issue a new helper Build before changing it.'
    }
} else {
    [IO.Directory]::CreateDirectory($destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $artifact
}
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $destination 'SHA256.txt'), "$hash  MediaMonitor-1.0.0beta-build1.apk`n", $utf8)
[IO.File]::WriteAllText((Join-Path $destination 'signature.txt'), ($verification -join "`n")+"`n", $utf8)
$manifest = [ordered]@{ applicationId='dev.petrov.mediamonitor'; versionName='1.0.0beta-build1'; versionCode=1;
    purpose='Instrument cluster diagnostics on the affected Android head unit'; minSdk=23; targetSdk=36;
    sha256=$hash; certificateSha256=$certificate; ymplayerUpdateEligible=$false }
[IO.File]::WriteAllText((Join-Path $destination 'build.json'), ($manifest | ConvertTo-Json)+"`n", $utf8)
Write-Output "Signed helper: $artifact ($hash)"
