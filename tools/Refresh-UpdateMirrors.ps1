# Run after pushing both manifests. Never purge before GitHub has the new content.
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$names = @('stable', 'manifest')
$expected = @{}
foreach ($name in $names) {
    $local = Get-Content -LiteralPath (Join-Path $projectRoot "update/$name.json") -Raw | ConvertFrom-Json
    $remote = Invoke-RestMethod -Uri "https://raw.githubusercontent.com/reziarlleh/YMPlayer2/main/update/$name.json" -TimeoutSec 20
    if ($remote.versionCode -ne $local.versionCode -or $remote.channel -ne $local.channel -or $remote.apk.sha256 -ne $local.apk.sha256) {
        throw "GitHub $name.json is not the prepared release. Push and verify before refreshing CDN."
    }
    $expected[$name] = $local
}
foreach ($name in $names) {
    $result = Invoke-RestMethod -Uri "https://purge.jsdelivr.net/gh/reziarlleh/YMPlayer2@main/update/$name.json" -TimeoutSec 20
    Write-Output "Requested CDN refresh for $name.json: $($result.status), request $($result.id)."
}
foreach ($hostName in @('cdn.jsdelivr.net', 'gcore.jsdelivr.net')) {
    foreach ($name in $names) {
        $url = "https://$hostName/gh/reziarlleh/YMPlayer2@main/update/$name.json"
        try {
            $remote = Invoke-RestMethod -Uri $url -TimeoutSec 20
            $local = $expected[$name]
            if ($remote.versionCode -eq $local.versionCode -and $remote.channel -eq $local.channel -and $remote.apk.sha256 -eq $local.apk.sha256) {
                Write-Output "Verified $hostName $name.json: $($remote.versionName), $($remote.channel)."
            } else {
                Write-Warning "$hostName $name.json still serves $($remote.versionName). CDN propagation is pending; verify again before declaring the mirror current."
            }
        } catch {
            Write-Warning "$hostName $name.json verification failed: $($_.Exception.Message)"
        }
    }
}
