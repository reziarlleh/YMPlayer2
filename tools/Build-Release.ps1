param([string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
if (-not $Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.signing\signing.properties'))) { throw 'Run Initialize-Signing.ps1 once, or restore the existing product key.' }
Push-Location $projectRoot
$lock = $null
try {
    $common = (& git rev-parse --git-common-dir).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Release allocation requires the product Git repository.' }
    $common = if ([IO.Path]::IsPathRooted($common)) { [IO.Path]::GetFullPath($common) } else { [IO.Path]::GetFullPath((Join-Path $projectRoot $common)) }
    # A common Git directory shares the lock and counter across linked worktrees.
    $lock = [IO.File]::Open((Join-Path $common 'ymplayer2-release.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $counterPath = Join-Path $common 'ymplayer2-build-number'
    $versionPath = Join-Path $projectRoot 'version.properties'
    $info = ConvertFrom-StringData (Get-Content -LiteralPath $versionPath -Raw)
    if ($info.baseVersion -notmatch '^\d+\.\d+\.\d+$' -or $info.channel -notin @('beta', 'stable')) { throw 'Invalid product version or channel.' }
    $last = [int]$info.lastIssuedBuild
    if (Test-Path -LiteralPath $counterPath) { $last = [Math]::Max($last, [int](Get-Content -LiteralPath $counterPath -Raw)) }
    $number = $last + 1
    $versionName = '{0}{1}-build{2}' -f $info.baseVersion, $(if ($info.channel -eq 'beta') { 'beta' } else { '' }), $number
    $destination = Join-Path $projectRoot "releases\$versionName"
    if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite an issued build.' }
    # Reserve before Gradle. Failures intentionally leave gaps; never reuse a number.
    [IO.File]::WriteAllText($counterPath, "$number")
    [IO.File]::WriteAllText($versionPath, "baseVersion=$($info.baseVersion)`nchannel=$($info.channel)`nlastIssuedBuild=$number`n")
    & .\gradlew.bat :app:assembleRelease "-PissuedBuildNumber=$number" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build $number failed and remains reserved." }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    $buildTools = Join-Path $Sdk 'build-tools\36.0.0'
    $badging = & (Join-Path $buildTools 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0 -or $badging[0] -notmatch "name='dev.petrov.ymplayer2' versionCode='$number' versionName='$([regex]::Escape($versionName))'") { throw 'APK identity/version verification failed.' }
    if ($badging -match '^application-debuggable') { throw 'Release must not be debuggable.' }
    $signature = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    New-Item -ItemType Directory -Path $destination | Out-Null
    $archived = Join-Path $destination "YMPlayer-$versionName.apk"
    Copy-Item -LiteralPath $apk -Destination $archived
    $sha = (Get-FileHash -LiteralPath $archived -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $destination 'SHA256.txt'), "$sha  $([IO.Path]::GetFileName($archived))`n")
    $signature | Set-Content -LiteralPath (Join-Path $destination 'signature.txt') -Encoding UTF8
    $badging | Set-Content -LiteralPath (Join-Path $destination 'badging.txt') -Encoding UTF8
    @{ applicationId = 'dev.petrov.ymplayer2'; versionName = $versionName; versionCode = $number; channel = $info.channel; sha256 = $sha; builtAtUtc = [DateTime]::UtcNow.ToString('o'); runtimeAcceptance = 'pending'; } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'build.json') -Encoding UTF8
    Write-Output "Verified artifact: $archived"
} finally { if ($null -ne $lock) { $lock.Dispose() }; Pop-Location }
