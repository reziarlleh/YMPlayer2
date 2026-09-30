param([Parameter(Mandatory = $true)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root 'app\src\androidTest\assets\clip-transport'
New-Item -ItemType Directory -Force $destination,(Join-Path $destination 'hls'),(Join-Path $destination 'dash') | Out-Null
$video = Join-Path $destination 'fixture.mp4'
& $Ffmpeg -y -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=160x90:rate=10:duration=16' -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=16' -c:v libx264 -preset ultrafast -crf 35 -g 20 -c:a aac -b:a 32k -shortest $video
if ($LASTEXITCODE -ne 0) { throw 'MP4 fixture generation failed' }
# FFmpeg's DASH muxer needs its working directory for relative segment templates on Windows.
foreach ($format in 'hls','dash') {
    Push-Location (Join-Path $destination $format)
    try {
        if ($format -eq 'hls') {
            & $Ffmpeg -y -hide_banner -loglevel error -i $video -c copy -hls_time 2 -hls_playlist_type vod -hls_segment_filename 'seg%02d.ts' index.m3u8
        } else {
            & $Ffmpeg -y -hide_banner -loglevel error -i $video -c copy -seg_duration 2 -use_template 1 -use_timeline 1 -f dash index.mpd
        }
        if ($LASTEXITCODE -ne 0) { throw "$format fixture generation failed" }
    } finally { Pop-Location }
}
