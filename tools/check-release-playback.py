"""Check an installed release with a user-selected folder of our two WAV fixtures.

Restricted to emulators. Start playback in the app before running. Uses system
media commands and a real process restart; never installs on physical devices.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial), 'Explicit emulator required'
a.output.mkdir(parents=True, exist_ok=True)
package = 'dev.petrov.ymplayer2'

def adb(*args):
    return subprocess.check_output([a.adb, '-s', a.serial, *args], timeout=30).decode('utf-8', errors='replace').replace('\r\n', '\n')

def session():
    dump = adb('shell', 'dumpsys', 'media_session')
    block = re.search(r'package=dev\.petrov\.ymplayer2\s+(.*?queueTitle=[^\n]*)', dump, re.S)
    if not block:
        return None
    text = block.group(1)
    match = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+)', text)
    if not match:
        return None
    return dict(state=match[1], position_ms=int(match[2]), metadata=re.search(r'metadata: ([^\n]*)', text)[1].strip(), dump=text)

def wait_for(predicate, timeout=15):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        last = session()
        if last and predicate(last):
            return last
        time.sleep(.4)
    raise AssertionError(last)

def key(code):
    adb('shell', 'input', 'keyevent', str(code))

results = {}
try:
    package_info = adb('shell', 'dumpsys', 'package', package)
    results['version'] = re.search(r'versionName=([^\s]+)', package_info)[1]
    results['playing'] = wait_for(lambda s: s['state'] == 'PLAYING')
    assert '01 - Evening route' in results['playing']['metadata'], 'Start the first fixture before this check'
    adb('shell', 'input', 'keyevent', '3')  # Activity leaves foreground.
    time.sleep(2)
    # Framework dumpsys positions are snapshots; pause publishes the actual audio clock.
    key(127)
    results['system_pause_background'] = wait_for(lambda s: s['state'] == 'PAUSED')
    assert results['system_pause_background']['position_ms'] > results['playing']['position_ms']
    key(126)
    results['system_play'] = wait_for(lambda s: s['state'] == 'PLAYING')
    (a.output / 'audioflinger.txt').write_text(adb('shell', 'dumpsys', 'media.audio_flinger'), encoding='utf-8')
    service = adb('shell', 'dumpsys', 'activity', 'services', package)
    (a.output / 'service.txt').write_text(service, encoding='utf-8')
    assert 'isForeground=true' in service, 'Playing service must have foreground ownership'
    key(87)
    results['system_next'] = wait_for(lambda s: s['state'] == 'PLAYING' and '02 - City lights' in s['metadata'])
    time.sleep(3)
    key(127)
    paused = wait_for(lambda s: s['state'] == 'PAUSED')
    results['before_restart'] = paused
    assert paused['position_ms'] >= 1000
    time.sleep(1)  # Let asynchronous preference writes finish.
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    restored = wait_for(lambda s: '02 - City lights' in s['metadata'])
    assert restored['state'] not in ('PLAYING', 'BUFFERING'), restored
    assert abs(restored['position_ms'] - paused['position_ms']) < 2000, restored
    results['restored_paused'] = restored
    results['passed'] = True
finally:
    (a.output / 'release-playback.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print('System media controls, background playback and paused process restoration passed.')
