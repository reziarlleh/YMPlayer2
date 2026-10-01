"""Editable vector concepts. No production resources or APK are changed.

Run with Python, then render SVGs using render.cjs and bundled Node/sharp.
"""
from pathlib import Path
from html import escape
import json
from itertools import count

OUT = Path(__file__).resolve().parent
MASK_IDS = count()
LOGO_04 = 'M5 18H23L40 42L58 18H75L48 54V84H31V54Z M48 54L75 18H95V84H78V42L63 62Z'
# All ascending letter edges and both cuts use direction (3,-4).
# Parallel centre lines: 4*x + 3*y = 352 and 380, 5.6 units apart; widths and gap are all 2.8.
# The left cut still covers the Y/M junction (48,54).
# The right cut ends in the counter below M's edge, before it reaches Y's leg.
CUTS_04 = 'M5.5 110L95.5 -10 M50 60L102.5 -10'
CONCEPTS = [
    dict(id='01', name='Оксид', mark='Стык', note='Плотный знак с общей диагональю и срезанными окончаниями.',
         bg='#191715', surface='#282420', raised='#36302A', text='#EFE5D7', muted='#B6AA9A',
         accent='#D77A50', onAccent='#191715', secondary='#B6B19A', paper='#EEE6DA'),
    dict(id='02', name='Лесная бумага', mark='Лигатура', note='Буквы связаны общим штрихом; мягкость без пузырчатых форм.',
         bg='#1C2420', surface='#29332C', raised='#364339', text='#F1EADC', muted='#ADB8A6',
         accent='#B6C489', onAccent='#1C2420', secondary='#C99673', paper='#E8EADF'),
    dict(id='03', name='Редакционный', mark='Клеймо', note='Узкая монограмма с засечками, как на корешке пластинки.',
         bg='#F0ECE2', surface='#E3DDD0', raised='#D5CEC0', text='#242926', muted='#625D58',
         accent='#8B3547', onAccent='#FFFFFF', secondary='#69785A', paper='#F4F0E7'),
    dict(id='04', name='Ночной эфир', mark='Разрез', note='Левая прорезь через угол YM; правая — только в верхней части.',
         bg='#151C29', surface='#232D3C', raised='#313E50', text='#EBE4D6', muted='#A3AFBE',
         accent='#DBB561', onAccent='#151C29', secondary='#91A1B1', paper='#E8E6DE'),
    dict(id='05', name='Сигнал', mark='Модуль', note='Ступенчатая сетка, плотная масса, выразительная маленькая иконка.',
         bg='#F7F5EF', surface='#E7E3DA', raised='#D9D4CA', text='#222321', muted='#696660',
         accent='#B93229', onAccent='#FFFFFF', secondary='#666C61', paper='#EFEDE6'),
    dict(id='06', name='Глина', mark='Петля', note='Один пластичный штрих: связь букв видна в нижней петле.',
         bg='#292326', surface='#383034', raised='#483E43', text='#F2E8E4', muted='#BEAFB5',
         accent='#D5B5A0', onAccent='#292326', secondary='#A5B7AE', paper='#EEE4DD'),
]

def rect(x,y,w,h,fill,r=0,stroke=None):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"'+(f' stroke="{stroke}"' if stroke else '')+'/>'
def text(x,y,s,size=22,color='#232321',weight=400,spacing=0,anchor='start'):
    return f'<text x="{x}" y="{y}" font-family="Segoe UI,Arial,sans-serif" font-size="{size}" font-weight="{weight}" letter-spacing="{spacing}" text-anchor="{anchor}" fill="{color}">{escape(s)}</text>'
def line(x1,y1,x2,y2,color,width=2):
    return f'<path d="M{x1} {y1}L{x2} {y2}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round"/>'
def circle(x,y,r,fill,stroke=None,width=2):
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{width}"' if stroke else '')+'/>'
def path(d,color,stroke=None,width=1,cap='butt',join='miter'):
    return f'<path d="{d}" fill="{color}"'+(f' stroke="{stroke}" stroke-width="{width}" stroke-linecap="{cap}" stroke-linejoin="{join}"' if stroke else '')+'/>'

def mark(c,color,knockout=None):
    n=int(c['id'])
    if n==1:
        # Broad common diagonal; Y tail and M right stem have different weights.
        return path('M4 18H21L38 41L57 18H73L47 52V84H31V52Z M47 52L73 18H94V84H78V40L59 63Z',color)
    if n==2:
        # The top junction is deliberately shared, rather than overlaying fonts.
        return path('M9 21L30 49L51 21V80M30 49V80M51 21L70 49L89 21V80','none',color,13,'round','round')
    if n==3:
        return '<g transform="translate(0 1) scale(.92)">'+path('M3 16H24V22H19L35 44L50 22H45V16H66V22H60L41 51V76H54V83H18V76H29V51L10 22H3Z M49 83V76H55V22H49V16H67L79 46L91 16H105V22H100V76H106V83H81V76H87V38L77 65H70L61 39V76H67V83Z',color)+'</g>'
    if n==4:
        # Apply the same shear to letters and cuts, preserving parallelism.
        # Real negative space, not white ink: works on any background.
        ident=f'ym4-cut-{next(MASK_IDS)}'
        mask=f'<defs><mask id="{ident}" maskUnits="userSpaceOnUse" x="-20" y="-20" width="140" height="140">'+rect(-20,-20,140,140,'white')+path(CUTS_04,'none','black',2.8)+'</mask></defs>'
        return '<g transform="translate(4 0) skewX(-7)">'+mask+f'<g mask="url(#{ident})">'+path(LOGO_04,color)+'</g></g>'
    if n==5:
        # Orthogonal Y/M share the central step, a deliberate compact counter.
        return path('M7 17H23V32H38V46H53V32H68V17H83V34H68V49H53V84H37V49H22V34H7Z M53 84V17H68V33H82V17H98V84H82V50H68V84Z',color)
    return path('M9 23C17 23 22 34 31 47C40 34 46 23 54 23M31 47V75Q31 85 42 85H46Q57 85 57 75V23L74 48L92 23V77','none',color,12,'round','round')

def logo(c,x,y,size,color,knockout=None):
    return f'<g transform="translate({x} {y}) scale({size/100})">{mark(c,color,knockout)}</g>'

def icon(name,x,y,color,size=24):
    shapes={
      'play':path('M8 4L23 14L8 24Z',color),
      'prev':path('M5 5H8V23H5ZM22 5L9 14L22 23Z',color),
      'next':path('M21 5H24V23H21ZM7 5L20 14L7 23Z',color),
      'stop':rect(6,6,16,16,color,1),
      'heart':path('M14 24L4 14C-2 5 8 0 14 8C20 0 30 5 24 14Z','none',color,2,'round','round'),
      'queue':path('M2 7H18M2 13H18M2 19H12M22 4V20M22 4H27','none',color,2,'round')+circle(19,21,3,color),
      'search':circle(11,11,8,'none',color,2.3)+line(17,17,25,25,color,2.3),
      'library':rect(3,4,19,19,'none',2,color)+path('M13 8V18M13 8H19','none',color,2)+circle(10,18,3,color),
      'video':rect(2,5,25,18,'none',3,color)+path('M11 9L19 14L11 19Z',color),
      'person':circle(14,8,4,'none',color,2)+path('M6 25V22Q6 15 14 15Q22 15 22 22V25','none',color,2),
      'settings':circle(14,14,9,'none',color,2)+circle(14,14,3,'none',color,2)+path('M14 1V5M14 23V27M1 14H5M23 14H27','none',color,2),
      'wave':path('M4 10V18M9 5V23M14 1V27M19 6V22M24 11V17','none',color,2,'round'),
      'more':circle(14,5,2,color)+circle(14,14,2,color)+circle(14,23,2,color),
      'repeat':path('M4 11V6H24L20 2M24 6L20 10M24 18V23H4L8 27M4 23L8 19','none',color,2,'round','round'),
      'shuffle':path('M3 5H7L21 23H26M22 19L26 23L22 27M3 23H7L12 17M17 10L21 5H26M22 1L26 5L22 9','none',color,2,'round','round'),
      'eq':path('M5 2V27M14 2V27M23 2V27M1 8H9M10 21H18M19 12H27','none',color,2,'round'),
    }
    return f'<g transform="translate({x} {y}) scale({size/28})">{shapes[name]}</g>'

def phone(c):
    b,s,r,t,m,a,oa=[c[k] for k in ['bg','surface','raised','text','muted','accent','onAccent']]
    parts=[rect(0,0,424,852,b,24),text(25,40,'9:41',15,t,600),text(395,40,'•••  85%',13,m,anchor='end'),
      logo(c,24,63,31,a,b),text(68,87,'YMPlayer 2',23,t,600),icon('person',332,65,t),icon('settings',374,65,t),
      text(24,134,'Сейчас играет',27,t,650)]
    # Identical, neutral fictitious cover in all concepts; no changing photos.
    parts += [rect(98,155,228,228,'#282929',14),text(116,180,'N A U T I L U S',9,'#BDB7AB',500,1),
      circle(212,262,83,'#141515'),circle(212,262,65,'none','#3F413F',1),
      circle(212,262,58,'none','#3F413F',1),circle(212,262,51,'none','#3F413F',1),
      circle(212,262,29,'#BFB8A9'),circle(212,262,5,'#282929'),
      path('M146 317Q190 261 230 203L261 191L232 240L207 306Z','#888D85'),
      text(116,365,'СЕРЕБРЯНЫЙ ВЕК',9,'#CFC8BA',500,1),
      text(24,420,'Титаник',31,t,650),text(24,451,'Наутилус Помпилиус',20,a,500),
      text(24,479,'Яндекс · Онлайн',13,m),icon('heart',343,409,a),icon('more',383,409,m),
      rect(24,508,376,4,r,2),rect(24,508,148,4,a,2),circle(172,510,6,a),
      text(24,541,'1:58',14,m),text(400,541,'5:03',14,m,anchor='end'),
      icon('prev',54,597,t,30),circle(151,611,40,a),icon('play',131,591,oa,40),
      icon('next',227,597,t,30),icon('stop',324,597,t,30),
      icon('repeat',30,672,m,25),icon('shuffle',95,672,m,25),icon('eq',284,672,m,25),icon('queue',365,672,m,25),
      line(24,728,400,728,r,1)]
    for i,(name,label) in enumerate([('play','Плеер'),('library','Медиатека'),('search','Поиск'),('video','Клипы')]):
        x=18+i*101
        if i==0: parts.append(rect(x+16,742,49,38,r,12))
        parts += [icon(name,x+29,748,a if i==0 else m),text(x+41,807,label,12,a if i==0 else m,600 if i==0 else 400,anchor='middle')]
    return ''.join(parts)

def board(c):
    paper=c['paper']; ink='#292A27'; muted='#686860'; a=c['accent']
    largeInk = c['text'] if c['id'] in ['03','05'] else c['bg']
    parts=[rect(0,0,1536,1024,paper),text(64,58,'YMPLAYER  /  ЦВЕТ И ЗНАК',15,muted,600,1.8),
      text(64,126,c['id']+'  '+c['name'],44,ink,650),text(66,162,c['mark'],23,muted,500),
      logo(c,78,218,280,largeInk,paper),
      line(410,235,410,487,'#C9C5BB',1),text(451,248,'ИКОНКА ПРИЛОЖЕНИЯ',13,muted,600,1),
      rect(451,270,142,142,c['bg'],30),logo(c,472,291,100,a,c['bg']),
      rect(624,270,142,142,'#F9F7F0',30),logo(c,645,291,100,largeInk,'#F9F7F0'),
      text(451,457,'Один цвет · узнаваемый силуэт',16,muted),
      text(65,556,c['note'],19,ink),text(65,599,'ПАЛИТРА И РОЛИ',13,muted,600,1.3)]
    palette=[('Фон',c['bg']),('Карточка',c['surface']),('Текст',c['text']),('Акцент',a),('Второй',c['secondary'])]
    for i,(name,col) in enumerate(palette):
        x=65+i*164
        parts += [rect(x,619,148,61,col,5,stroke='#BEBBB0'),text(x,705,name,15,muted),text(x,731,col,17,ink,600)]
    parts += [text(65,790,'МАЛЕНЬКИЙ РАЗМЕР',13,muted,600,1),
      logo(c,65,809,64,largeInk,paper),logo(c,160,834,32,largeInk,paper),logo(c,224,842,24,largeInk,paper),
      text(279,860,'64 / 32 / 24 px',16,muted),
      rect(65,907,160,42,c['bg'],9),text(145,935,'Воспроизвести',15,a,600,anchor='middle'),
      rect(245,907,150,42,a,9),text(320,935,'Моя волна',15,c['onAccent'],600,anchor='middle'),
      text(65,984,'ЭСКИЗ ДЛЯ ВЫБОРА  •  01.10.2026',12,muted,500,1),
      f'<g transform="translate(1048 94)">{phone(c)}</g>',
      text(1048,975,'Пример палитры на главном плеере',15,muted)]
    return svg(''.join(parts),1536,1024)

def svg(body,w,h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{body}</svg>'

def luminance(hex):
    values=[int(hex[i:i+2],16)/255 for i in (1,3,5)]
    rgb=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in values]
    return sum(a*b for a,b in zip(rgb,[.2126,.7152,.0722]))
def contrast(a,b):
    high,low=sorted([luminance(a),luminance(b)],reverse=True)
    return round((high+.05)/(low+.05),2)

rows=[]
for c in CONCEPTS:
    stem=c['id']+'-concept'
    (OUT/(stem+'.svg')).write_text(board(c),encoding='utf-8')
    for suffix,col,knockout in [('logo',c['accent'],None),('logo-mono','#222321',None)]:
        # Negative-space cuts remain transparent in the exported mark.
        body=mark(c,col,knockout)
        (OUT/(c['id']+'-'+suffix+'.svg')).write_text(svg(body,100,100),encoding='utf-8')
    ratios={key:contrast(c[a],c[b]) for key,a,b in [('text/bg','text','bg'),('text/surface','text','surface'),('muted/bg','muted','bg'),('accent/bg','accent','bg'),('button','onAccent','accent')]}
    assert min(ratios.values())>=4.5,(c['id'],ratios)
    c['contrast']=ratios
    rows.append(f"| {c['id']} · {c['name']} | {c['mark']} | `{c['bg']}` | `{c['accent']}` | {ratios['text/bg']}:1 | {ratios['button']}:1 |")

(OUT/'palettes.json').write_text(json.dumps(CONCEPTS,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'README.md').write_text('''# Цвет и монограмма YMPlayer — варианты для выбора

Дата: 2026-10-01. Прямой запрос владельца: отказаться от текущего cyan/magenta,
предложить более характерные палитры и несколько новых совмещённых YM.
Владелец выбрал №4 «Ночной эфир». В правке r2 широкая косая полоса заменена
двумя тонкими сквозными прорезями; диагонали букв и прорезей параллельны.
Правка r3: ширина увеличена с 1,5 до 2 единиц; обе прорези сдвинуты влево
на 9,5 единицы, правая проходит через внутренний угол Y/M (48,54).
Владелец одобрил r3 и попросил ещё один вариант r4 для сравнения:
обе прорези сдвинуты на 6 единиц вправо, теперь левая проходит через угол.
У правой убрано нижнее продолжение через ножку Y. Ширина и наклон сохранены.
Следующая правка r5: обе прорези расширены наружу; их внутренние границы
сохранены. Толщина каждой прорези и прежний просвет равны 2,8 единицы.
APK/resources PRISM не изменены; сейчас дорабатывается выбранный знак.
Обновление базовой айдентики не означает запуск импорта скинов M13/2.1.0.

Шесть самостоятельных SVG-композиций, экспортированные PNG, SVG-знаки с прозрачным
фоном и одноцветные версии. Шрифт демонстрации — системный Segoe UI; монограммы
нарисованы контурами/штрихами, не зависят от шрифта. Внешние фото/обложки не
использовались: обложка на макете — одинаковая нейтральная векторная иллюстрация.
Метки 64/32/24 относятся к исходному SVG-борду, не к уменьшенной общей картинке.

| Вариант | Знак | Фон | Акцент | Текст/фон | Текст кнопки/акцент |
| --- | --- | --- | --- | --- | --- |
'''+ '\n'.join(rows)+'''

Палитру и форму знака можно выбирать независимо. В макетах показана тёмная либо
светлая основная композиция, а не полностью разработанная пара Light/Dark.
Контраст рассчитан формулой WCAG для приведённых пар sRGB; фокус, disabled,
ошибки и все экраны приложения потребуют проверки после выбора. Это не
утверждение о сертификации готового интерфейса.

## Файлы и повторное получение

- `01-concept.svg` … `06-concept.svg`: редактируемые композиции 1536×1024.
- `01-logo.svg` … `06-logo.svg`: акцентные прозрачные знаки, 100×100.
- `*-logo-mono.svg`: одноцветные знаки; проверить читаемость перед окончательным выбором.
- `*-concept.png`: просмотр. `pair-01-02.png` и остальные пары — сравнение.
- `index.html`: локальная галерея с увеличением и ссылками на SVG.
- `palettes.json`: точные значения ролей и рассчитанные контрасты.
- `04-revision-r5.svg/png`: текущая правка с равной толщиной прорезей и просвета.
- `04-revision-r4.svg/png`: сохранённая предыдущая правка.
- `04-revision-r3.svg/png`, `04-logo-r3.svg`, `04-logo-mono-r3.svg`: одобренный r3, сохранён для сравнения.
- `04-revision-r2.svg/png`: сохранённая предыдущая правка.
- `original-04/`: исходный №4 до правки, сохранён для сравнения.
- `build_concepts.py` создаёт SVG/JSON/эту памятку; `render.cjs` экспортирует PNG
  через установленный sharp (путь библиотеки задаётся переменной NODE_PATH).

Дальше: финализация выбранной геометрии №4 → оптическая правка монограммы/контрформ →
полная Light/Dark-пара и состояния → перенос в базовые токены/Android-вектор
→ UI-проверка на основных размерах без изменения поведения плеера.
''',encoding='utf-8')
print('Six vector directions built; listed contrast pairs all >= 4.5:1.')

c=CONCEPTS[3]
detail=rect(0,0,1400,840,c['bg'])+text(70,84,'04  Ночной эфир',44,c['text'],600)+text(70,125,'Толщина каждой прорези равна просвету между ними',21,c['muted'])
detail+=logo(c,80,180,490,c['accent'])
detail+=text(740,207,'ИКОНКА И ОДНОЦВЕТНЫЙ ЗНАК',16,c['muted'],600,1)
detail+=rect(740,237,210,210,c['surface'],38)+logo(c,761,258,168,c['accent'])
detail+=rect(989,237,210,210,c['paper'],38)+logo(c,1010,258,168,c['bg'])
detail+=text(740,510,'МАЛЕНЬКИЕ РАЗМЕРЫ',16,c['muted'],600,1)
for x,size in [(752,96),(898,64),(1015,48),(1125,32)]:
    detail+=logo(c,x,547+(96-size)/2,size,c['accent'])+text(x+size/2,686,str(size)+' px',16,c['muted'],anchor='middle')
detail+=text(70,767,'Прорези прозрачные: цвет внутри них задаёт фон.',20,c['text'])
detail+=text(70,807,'Эскиз r5 · 01.10.2026',14,c['muted'])
(OUT/'04-revision-r5.svg').write_text(svg(detail,1400,840),encoding='utf-8')
