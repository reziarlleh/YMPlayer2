"""Verify hierarchy and double Back on an emulator with the signed APK playing."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial), 'Explicit emulator required'
a.output.mkdir(parents=True, exist_ok=True)
package = 'dev.petrov.ymplayer2'

def adb(*args):
    return subprocess.check_output([a.adb, '-s', a.serial, *args], timeout=30)

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ym2-navigation.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/ym2-navigation.xml'))

def click(label):
    nodes = [n for n in tree().iter('node') if label in (n.get('text'), n.get('content-desc'))]
    assert nodes, f'Control missing: {label}'
    left, top, right, bottom = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))

def focused():
    return re.search(r'mCurrentFocus=.*', adb('shell', 'dumpsys', 'window').decode('utf-8'))[0]

def media():
    text = adb('shell', 'dumpsys', 'media_session').decode('utf-8')
    block = re.search(r'package=dev\.petrov\.ymplayer2\s+(.*?queueTitle=[^\n]*)', text, re.S)[1]
    state, position = re.search(r'state=PlaybackState \{state=(\w+)(?:\(\d+\))?, position=(\d+)', block).groups()
    state = {'0': 'NONE', '1': 'STOPPED', '2': 'PAUSED', '3': 'PLAYING', '6': 'BUFFERING'}.get(state, state)
    return {'state': state, 'position_ms': int(position)}

result = {}
try:
    info = adb('shell', 'dumpsys', 'package', package).decode('utf-8')
    result['version'] = re.search(r'versionName=([^\s]+)', info)[1]
    result['before'] = media()
    assert result['before']['state'] == 'PLAYING', 'Start real fixture playback first'
    click('Медиатека'); click('Поиск'); click('Медиатека')
    click('Общий каталог'); click('Папки с музыкой')
    click('Назад')
    assert any(n.get('text') == 'Треки' for n in tree().iter('node')), 'Folders must ascend to library'
    click('Назад')
    assert not any(n.get('content-desc') == 'Назад' for n in tree().iter('node')), 'Library must ascend to player'
    result['parents_ignore_history'] = True
    started = time.monotonic()
    adb('shell', 'input', 'keyevent', '4')
    result['first_back_keeps_activity'] = package in focused()
    assert result['first_back_keeps_activity']
    (a.output / 'double-back-hint.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    assert time.monotonic() - started < 1.8, 'Host was too slow to send a double Back'
    adb('shell', 'input', 'keyevent', '4')
    for _ in range(20):
        if package not in focused(): break
        time.sleep(.2)
    else: raise AssertionError('Second Back did not close the Activity')
    result['second_back_closes_activity'] = True
    assert media()['state'] == 'PLAYING', 'Exiting the interface must keep background music'
    adb('shell', 'input', 'keyevent', '127')
    time.sleep(.4)
    result['after_exit_paused_by_system'] = media()
    assert result['after_exit_paused_by_system']['state'] == 'PAUSED'
    assert result['after_exit_paused_by_system']['position_ms'] > result['before']['position_ms']
    adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    result['passed'] = True
finally:
    (a.output / 'release-navigation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('Hierarchy, double Back and continuing background audio passed.')
