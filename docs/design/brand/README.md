# YMPlayer 2 — утверждённая айдентика

Дата: 2026-10-01. Монограмма №4 / r5; палитра №1 «Оксид».
Левая прорезь проходит через внутренний угол Y/M. Правая заканчивается
выше ножки Y. Толщина каждой прорези равна исходному просвету: 2,8 единицы;
внутренние границы просвета сохранены. Скос 7° применяется ко всему знаку.

`ym-logo.svg` и `ym-logo-mono.svg` — настоящие контуры с прозрачными прорезями,
без фонового перекрытия или масок. Compose и Android используют ту же геометрию.
`launcher-preview` показывает adaptive foreground на графитовом фоне;
системный launcher выбирает внешний контур. TV `tv-banner` — 16:9 с названием
в векторных контурах Bahnschrift; файл шрифта не входит в проект.

Production banner: mdpi 160×90, hdpi 240×135, xhdpi 320×180,
xxhdpi 480×270, xxxhdpi 640×360. Icon + text и безопасные поля проверяются
на настоящем TV launcher. [Android TV guidelines](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines).

Генерация: `python tools/build-brand.py` (shapely, fonttools, системный
Bahnschrift), затем `node tools/render-brand.cjs` (sharp). Генератор сохраняет
исторически одобренные параметры; исторические варианты не перезаписывает.
Встроенное оформление имеет исторический id `prism` и имя «Оксид».
Все UI-палитры живут в designsystem/skin: `PrismSkin.kt` задаёт семантические
Light/Dark-токены, `NativeSkin.kt` передаёт их в native-панель клипов и хранит
отдельные белые роли SideBar. Изменение значений не требует правок commands,
provider или playback. Системный launcher и TV banner — брендовые ресурсы APK.
Импорт пользовательских пакетов, preview и темы относятся к 2.1.0.
