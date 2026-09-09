"""Native layout smoke check. Explicit emulator serial only; no physical devices.

Run after instrumentation, with the tested APK already installed. Uses real
Android UI hierarchy and screenshots; it does not render or alter the images.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='dev.petrov.ymplayer2.dev')
parser.add_argument('--output', required=True)
parser.add_argument('--case', help='Optional single case, for example 1280x800@1.3')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    raise SystemExit('This check is restricted to an explicitly named emulator.')
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)

def adb(*command, binary=False):
    result = subprocess.run([args.adb, '-s', args.serial, *command], check=True, capture_output=True, timeout=45)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ym2-window.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/ym2-window.xml'))

def find(label):
    return next((n for n in hierarchy().iter('node') if label in (n.get('text'), n.get('content-desc'))), None)

def click(label, scroll=False):
    for _ in range(9 if scroll else 1):
        nodes = list(hierarchy().iter('node'))
        node = next((n for n in nodes if label in (n.get('text'), n.get('content-desc'))), None)
        if node is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            assert x2 > x1 and y2 > y1, (label, node.attrib)
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(.2)
            return
        scroll_bounds = [tuple(map(int, re.findall(r'\d+', n.get('bounds')))) for n in nodes if n.get('scrollable') == 'true']
        scroll_bounds = [b for b in scroll_bounds if b[2]-b[0] > width/2 and b[3]-b[1] > 80]
        if not scroll_bounds:
            break
        x1, y1, x2, y2 = max(scroll_bounds, key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
        # Scroll the actual content viewport; mini-player/rail are independent surfaces.
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str(int(y1+(y2-y1)*.85)), str((x1+x2)//2), str(int(y1+(y2-y1)*.15)), '250')
    raise AssertionError('Control not found: ' + label)

def capture(name):
    hierarchy()  # Wait for Android to settle before capturing the real frame.
    (output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def launch():
    adb('shell', 'am', 'force-stop', args.package)
    adb('shell', 'am', 'start', '-W', '-n', args.package + '/dev.petrov.ymplayer2.MainActivity')
    time.sleep(.6)

results = []
cases = [(360, 640, 1.0), (736, 360, 1.0), (1024, 600, 1.0), (360, 640, 2.0), (736, 360, 2.0)]
if args.case:
    match = re.fullmatch(r'(\d+)x(\d+)@(\d+(?:\.\d+)?)', args.case)
    if not match:
        raise SystemExit('Case format: WIDTHxHEIGHT@FONT_SCALE')
    cases = [(int(match[1]), int(match[2]), float(match[3]))]
try:
    for w, h, scale in cases:
        width, height = w*2, h*2
        adb('shell', 'wm', 'size', f'{width}x{height}')
        adb('shell', 'wm', 'density', '320')
        adb('shell', 'settings', 'put', 'system', 'font_scale', str(scale))
        launch()
        prefix = f'{w}x{h}-font{scale}'
        capture(prefix + '-dark')
        click('Воспроизвести', scroll=True)
        assert find('Пауза') is not None
        capture(prefix + '-controls')
        click('Медиатека')
        assert find('Пауза') is not None, 'Mini-player lost playing state'
        capture(prefix + '-library')
        click('Настройки')
        click('Светлая', scroll=True)
        click('Плеер')
        capture(prefix + '-light')
        results.append({'widthDp': w, 'heightDp': h, 'fontScale': scale, 'playAndMiniPlayer': 'passed'})
        print(prefix + ' passed', flush=True)
    (output / 'verification.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
finally:
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'wm', 'density', 'reset')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
