"""Generate original test audio and an embedded geometric cover; requires FFmpeg."""
import argparse
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--ffmpeg', required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
destination = root / 'app/src/androidTest/assets/cover.mp3'
destination.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ymplayer-cover-fixture-') as directory:
    image = Path(directory) / 'cover.ppm'
    pixels = bytes(c for y in range(640) for x in range(640)
                   for c in ((20, 200, 220) if x < 320 else (220, 30, 150)))
    image.write_bytes(b'P6\n640 640\n255\n' + pixels)
    subprocess.run([a.ffmpeg, '-y', '-f', 'lavfi', '-i', 'sine=frequency=523:duration=30',
                    '-i', str(image), '-map', '0:a', '-map', '1:v', '-c:a', 'libmp3lame',
                    '-b:a', '32k', '-c:v', 'mjpeg', '-id3v2_version', '3', '-metadata',
                    'title=Cover fixture', '-metadata', 'artist=YMPlayer tests', '-metadata',
                    'album=Original test artwork', '-metadata:s:v', 'title=Album cover',
                    '-metadata:s:v', 'comment=Cover (front)', str(destination)], check=True)
print(destination)
