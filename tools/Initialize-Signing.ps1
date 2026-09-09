param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME to JDK 17.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$signingRoot = Join-Path $projectRoot '.signing'
if (Test-Path -LiteralPath $signingRoot) { throw '.signing already exists. Do not replace an existing product key.' }
New-Item -ItemType Directory -Path $signingRoot | Out-Null
$bytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($bytes)
$random.Dispose()
$password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$previousPassword = $env:YM2_SIGNING_PASSWORD
try {
    $env:YM2_SIGNING_PASSWORD = $password
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -keystore (Join-Path $signingRoot 'ymplayer2.jks') -storetype JKS -storepass:env YM2_SIGNING_PASSWORD -keypass:env YM2_SIGNING_PASSWORD -alias ymplayer2 -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=YMPlayer 2, OU=Android, O=YMPlayer 2'
    if ($LASTEXITCODE -ne 0) { throw 'Independent key generation failed.' }
    [IO.File]::WriteAllText((Join-Path $signingRoot 'signing.properties'), "storePassword=$password`nkeyPassword=$password`n")
    Write-Output 'Independent YMPlayer 2 signing key created in ignored .signing. Keep a private backup of this directory.'
} finally { $env:YM2_SIGNING_PASSWORD = $previousPassword; $password = $null }
