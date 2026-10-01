"""Preview only: approved YM + outlined Arial Black Player2. No APK assets written."""
from pathlib import Path
import math
import re
import xml.etree.ElementTree as ET
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

OUT = Path(__file__).resolve().parent
source = ET.parse(OUT.parent / 'ym-logo.svg').getroot()
mark_path = source.find('{http://www.w3.org/2000/svg}path').attrib['d']
coords = [(float(x), float(y)) for x, y in re.findall(r'[ML](-?[\d.]+),(-?[\d.]+)', mark_path)]
left, right = min(x for x,y in coords), max(x for x,y in coords)
top, bottom = min(y for x,y in coords), max(y for x,y in coords)
font = TTFont('C:/Windows/Fonts/ariblk.ttf')
glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
cap = BoundsPen(glyphs)
glyphs[cmap[ord('P')]].draw(cap)
scale = (bottom - top) / cap.bounds[3]
slope = math.tan(math.radians(7))
pen, bounds = SVGPathPen(glyphs), BoundsPen(glyphs)
cursor = 0
previous = None
kern = {}
if 'kern' in font:
    for table in font['kern'].kernTables:
        kern.update(table.kernTable)
for char in 'Player2':
    name = cmap[ord(char)]
    cursor += kern.get((previous, name), 0)
    transform = (scale, 0, scale*slope, -scale, cursor*scale, bottom-top)
    glyphs[name].draw(TransformPen(pen, transform))
    glyphs[name].draw(TransformPen(bounds, transform))
    cursor += glyphs[name].width
    previous = name
tx = (right-left) + 7 - bounds.bounds[0]
min_y = min(0, bounds.bounds[1])
height = max(bottom-top, bounds.bounds[3]) - min_y
width = tx + bounds.bounds[2]

def logo(ink, copper='#D77A50'):
    return (f'<g transform="translate(0 {-min_y})">'
        f'<path transform="translate({-left} {-top})" fill="{copper}" fill-rule="evenodd" d="{mark_path}"/>'
        f'<path transform="translate({tx} 0)" fill="{ink}" d="{pen.getCommands()}"/></g>')

def svg(body, w, h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{body}</svg>\n'

def placed(ink, x, y, max_w, max_h):
    factor = min(max_w/width, max_h/height)
    return f'<g transform="translate({x+(max_w-width*factor)/2} {y+(max_h-height*factor)/2}) scale({factor})">{logo(ink)}</g>'

for name, ink in [('wide-white','#FFFFFF'), ('wide-black','#000000')]:
    (OUT / (name+'.svg')).write_text(svg(logo(ink),width,height),encoding='utf-8')
board = '<rect width="1280" height="360" fill="#191715"/><rect y="360" width="1280" height="360" fill="#EEE6DA"/>'
board += placed('#FFFFFF',64,70,1152,230) + placed('#000000',64,430,1152,230)
(OUT / 'wide-sketch.svg').write_text(svg(board,1280,720),encoding='utf-8')
for name,bg,ink in [('tv-dark','#191715','#FFFFFF'),('tv-light','#EEE6DA','#000000')]:
    body = f'<rect width="1280" height="720" fill="{bg}"/>' + placed(ink,96,140,1088,440)
    (OUT / (name+'.svg')).write_text(svg(body,1280,720),encoding='utf-8')
print(f'Preview only: Arial Black, 7 degrees, Player2. Wordmark {width:.2f} x {height:.2f}')
