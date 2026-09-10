# Test media

`cover.mp3` is an original generated fixture: a 30-second 523 Hz sine wave,
32 kbit/s MP3, with a simple cyan/magenta image embedded as an ID3v2.3 front cover.
It contains no user music or downloaded artwork. Generated locally with FFmpeg
for metadata, thumbnail and playback regression checks. It ships only in the
instrumentation test APK, never in the application APK.

Regenerate with `python tools/make-test-artwork.py --ffmpeg PATH_TO_FFMPEG` from
the repository root. FFmpeg is needed only to regenerate this fixture.
