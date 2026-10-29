param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Notes,
    [string]$DistributionRef
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ($Version -notmatch '^\d+\.\d+\.\d+(beta)?-build\d+$') { throw 'Invalid product version.' }
$projectVersion = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $root 'version.properties') -Raw)
if ($projectVersion.releaseState -eq 'development' -and $Version.StartsWith($projectVersion.baseVersion)) {
    throw 'This version has not been accepted for publication. Keep the stable feed unchanged.'
}
$release = Join-Path $root "releases\$Version"
$apkName = "YMPlayer-$Version.apk"
$apk = Join-Path $release $apkName
$metadata = Get-Content -LiteralPath (Join-Path $release 'build.json') -Raw | ConvertFrom-Json
if ($metadata.versionName -ne $Version -or $metadata.applicationId -ne 'dev.petrov.ymplayer2') {
    throw 'Release metadata does not match the 2.x product.'
}
if ($metadata.channel -ne 'stable') {
    throw 'Prereleases are manual only. Do not write either automatic update feed before stable acceptance.'
}
if (-not $metadata.minSdk) { throw 'Build metadata must declare the minimum SDK verified from the APK.' }
if (-not (Test-Path -LiteralPath $apk)) { throw 'Signed release APK is missing.' }
$taggedApk = "releases/$Version/$apkName"
if (-not $DistributionRef) {
    & git -C $root show-ref --verify --quiet "refs/tags/apk-$Version"
    if ($LASTEXITCODE -eq 0) { $DistributionRef = "apk-$Version" }
    elseif ($LASTEXITCODE -eq 1) { $DistributionRef = "v$Version" }
    else { throw 'Unable to inspect the distribution tag.' }
}
if ($DistributionRef -notin @("v$Version", "apk-$Version")) {
    throw 'DistributionRef must be the exact vVERSION or apk-VERSION tag for this release.'
}
$distributionCommit = ([string](& git -C $root rev-parse --verify "refs/tags/$DistributionRef^{commit}")).Trim()
if ($LASTEXITCODE -ne 0) { throw "Distribution tag $DistributionRef does not exist." }
$entry = ([string](& git -C $root ls-tree $distributionCommit -- $taggedApk)).Trim()
if ($LASTEXITCODE -ne 0 -or $entry -notmatch '^100644 blob ([a-f0-9]{40,64})\t') {
    throw 'The distribution tag must contain the original release APK as an ordinary Git blob.'
}
$apkBlob = $Matches[1]
$treeEntries = @(& git -C $root ls-tree -r -l $distributionCommit)
if ($LASTEXITCODE -ne 0) { throw 'Unable to measure the complete distribution tree.' }
[long]$treeBytes = 0
foreach ($treeEntry in $treeEntries) {
    if ($treeEntry -notmatch '^\d+ blob [a-f0-9]{40,64}\s+([0-9]+)\t') {
        throw 'Distribution tree contains an unsupported non-file entry.'
    }
    $treeBytes += [long]$Matches[1]
}
if ($treeBytes -ge 50000000) {
    throw "Distribution tree is $treeBytes bytes: jsDelivr GitHub packages must be below 50 MB. Run tools/Create-DistributionTag.py --version $Version, review and push only its new apk-$Version tag, then retry."
}
$file = Get-Item -LiteralPath $apk
if ($file.Length -gt 20MB) { throw 'The APK exceeds the jsDelivr GitHub-file limit of 20 MB.' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $metadata.sha256) { throw 'Signed release APK differs from build.json.' }
# Hash Git's raw binary output through a stream. PowerShell's text pipeline must
# never re-encode APK bytes while validating the immutable version-pinned URL.
$gitBinary = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $gitBinary
$start.Arguments = "cat-file blob $apkBlob"
$start.WorkingDirectory = $root
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $start
$algorithm = [Security.Cryptography.SHA256]::Create()
try {
    if (-not $process.Start()) { throw 'Unable to read the tagged APK blob.' }
    $errorText = $process.StandardError.ReadToEndAsync()
    $tagHash = [BitConverter]::ToString($algorithm.ComputeHash($process.StandardOutput.BaseStream)).Replace('-', '').ToLowerInvariant()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Unable to hash tagged APK: $($errorText.Result)" }
    if ($tagHash -ne $hash) { throw 'Distribution-tag APK differs from the signed local APK/build.json.' }
} finally { $algorithm.Dispose(); $process.Dispose() }
$manifest = [ordered]@{
    schemaVersion = 1
    packageName = 'dev.petrov.ymplayer2'
    versionCode = [int]$metadata.versionCode
    versionName = $Version
    channel = $metadata.channel
    minSdk = [int]$metadata.minSdk
    publishedAt = [DateTime]::UtcNow.ToString('o')
    releasePageUrl = "https://github.com/reziarlleh/YMPlayer2/releases/tag/v$Version"
    releaseNotes = $Notes
    apk = [ordered]@{
        primaryUrl = "https://github.com/reziarlleh/YMPlayer2/releases/download/v$Version/$apkName"
        alternativeUrl = "https://cdn.jsdelivr.net/gh/reziarlleh/YMPlayer2@$DistributionRef/releases/$Version/$apkName"
        sizeBytes = $file.Length
        sha256 = $hash
    }
}
$target = Join-Path $root 'update\manifest.json'
New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $target -Encoding UTF8
Write-Output "Prepared $target with primary GitHub and immutable jsDelivr ref $DistributionRef ($treeBytes tree bytes)."
if ($metadata.channel -eq 'stable') {
    $stable = Join-Path $root 'update\stable.json'
    Copy-Item -LiteralPath $target -Destination $stable -Force
    Write-Output "Prepared $stable for stable-channel installations."
}
