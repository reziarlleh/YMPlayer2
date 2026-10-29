# Run after pushing both manifests. Never purge before GitHub has the new content.
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$names = @('stable', 'manifest')
$expected = @{}
foreach ($name in $names) {
    $local = Get-Content -LiteralPath (Join-Path $projectRoot "update/$name.json") -Raw | ConvertFrom-Json
    $remote = Invoke-RestMethod -Uri "https://raw.githubusercontent.com/reziarlleh/YMPlayer2/main/update/$name.json" -TimeoutSec 20
    if ($remote.versionCode -ne $local.versionCode -or $remote.channel -ne $local.channel -or $remote.apk.sha256 -ne $local.apk.sha256 -or
        $remote.apk.primaryUrl -ne $local.apk.primaryUrl -or $remote.apk.alternativeUrl -ne $local.apk.alternativeUrl -or
        $remote.releaseNotes -ne $local.releaseNotes) {
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
            if ($remote.versionCode -eq $local.versionCode -and $remote.channel -eq $local.channel -and $remote.apk.sha256 -eq $local.apk.sha256 -and
                $remote.apk.primaryUrl -eq $local.apk.primaryUrl -and $remote.apk.alternativeUrl -eq $local.apk.alternativeUrl -and
                $remote.releaseNotes -eq $local.releaseNotes) {
                Write-Output "Verified $hostName $name.json: $($remote.versionName), $($remote.channel)."
            } else {
                Write-Warning "$hostName $name.json metadata differ from main (version $($remote.versionName), APK URL $($remote.apk.alternativeUrl)). CDN propagation is pending; verify the complete metadata before declaring the mirror current."
            }
        } catch {
            Write-Warning "$hostName $name.json verification failed: $($_.Exception.Message)"
        }
    }
}
