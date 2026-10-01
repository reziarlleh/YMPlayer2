# Финальный 2.0.1 — айдентика «Оксид»

Дата: 2026-10-01. Владелец утвердил монограмму №4 / r5 и отдельно палитру №1.
[Release](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.0.1-build60).
[Исходники и генерация](design/brand/README.md). [Следующий этап M13](M13_SKINS_PLAN.md).

## Изменения

- Утверждённая геометрия перенесена в прозрачные контуры SVG, Compose и adaptive
  launcher. Две прорези, их просвет и положение сохранены; ARTWORK и бренд в rail
  используют ту же монограмму. [Сравнение alpha](qa/brand-2-0-1/geometry.json)
  с одобренным r5 на 1000×1000: средняя абсолютная ошибка 0,0000054.
- Палитра «Оксид»: медь/тёплый графит, согласованный Light, все Material surface
  containers, native-панель клипов и фон SideBar. Белые кнопки K4811 сохранены.
  Принятая диагональ клипов и размеры управления не изменены.
- TV-плитка 16:9 содержит знак и контурное название; пять плотностей 160×90
  до 640×360. [Настоящий launcher](qa/brand-2-0-1/tv-launcher.png) после удаления
  одноимённых debug-установок запускает [релизный package](qa/brand-2-0-1/tv-launch-target.txt).

## Проверки

| Проверка | Результат |
| --- | --- |
| Debug APK/test APK и lint | Сборка прошла; lint без Errors/Fatal, 30 предупреждений существующего проекта |
| Android 15 | [15 native](qa/brand-2-0-1/phone-native.txt): shell, PlayerComposition, SkinPlayback, ClipControls, ClipQueueInfo |
| Android 15, 200% | [3 PlayerComposition](qa/brand-2-0-1/phone-font200.txt); [снимок](qa/brand-2-0-1/player-phone-font200.png), controls без прокрутки |
| TV29 | [12 native](qa/brand-2-0-1/tv-native.txt) + [D-pad отдельно](qa/brand-2-0-1/tv-focus-dpad.txt) |
| После последней правки Light/видео | [4 Android](qa/brand-2-0-1/phone-final-colors.txt) и [2 TV](qa/brand-2-0-1/tv-final-colors.txt) прошли |
| Цвета | [8 текстовых пар](qa/brand-2-0-1/contrast.json) ≥4,5:1; это не сертификат всех UI-состояний |
| Signed57 → Signed59 | [Даты установки сохранены](qa/brand-2-0-1/upgrade.json) на Android/TV. Телефон сохранил City lights на паузе 0:03; установка через adb -r, не новый прогон системного updater |
| Signed WAV | [passed=true](qa/brand-2-0-1/release-playback/release-playback.json): два настоящих WAV, фон, системные Pause/Play/Next, COLD restart, второй трек на паузе 0:03 |

Начальный TV shell-прогон не получил initial focus: предыдущие touch-сценарии
оставили эмулятор в touch mode. Это воспроизводилось отдельно; после реального
DPAD-события и нового запуска проверка прошла. Production-focus не менялся.
[Исходный результат](qa/brand-2-0-1/tv-native-first.txt) сохранён.
Первый запуск signed-smoke был ошибочно начат со второго WAV; после выбора
первого весь сценарий прошёл. Код playback не менялся для прохождения проверки.

Build58 был подписан, но не выпущен: Light-secondary на surfaceVariant давал
4,29:1. Build59 затемняет только этот текст до `#685C50` (4,64:1); 58 сохранён
как кандидат и его номер не переиспользован. Скриншоты нормального плеера,
200% и клипов получены до этой точечной правки; [Light](qa/brand-2-0-1/player-light.png)
и [signed-плеер](qa/brand-2-0-1/release-player-phone.png) сняты в финальном 59.

## Проверочный артефакт build59

`2.0.1-build59`, versionCode 59, channel stable,
package `dev.petrov.ymplayer2`, размер 4,816,438 байт.
SHA-256 `5c9d45cb0a2ee4face7bfca9723445b42bd752c805362e2cedde9c3923860204`.
Прежний signer SHA-256:
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Сборочный скрипт подтвердил package/version/signature, R8 и lintVital прошли.
Playback/provider/updater-контракты, ключ и данные не изменены.
`runtimeAcceptance=pending` относится к независимой аппаратной приёмке.

Публикация и живые источники APK проверяются перед закрытием выпуска.

## Финал build60

После замечания владельца прямые цвета native-клипов и SideBar перенесены
в designsystem/skin/NativeSkin.kt. ClipPalette выводится из AppSkin.dark;
native Views получают визуальные данные. Белые роли K4811 отдельные.
Полный signed-playback прогон выше был избыточен для визуальной задачи;
после переноса палитры он не повторяется. Финал проверяется сборкой, lint,
снимками оформления и установкой APK. Build59 не публикуется как финальный.

Финальный signed APK: `2.0.1-build60`, code60, stable, 4816434 байт,
SHA-256 `f7b6fdaa920a6c66b9ee70bf0e780cee070c05f3a0620223b1839d07d4df7f99`. Signer прежний.
Debug/test APK и lint прошли; [две native-проверки отображения](qa/brand-2-0-1/native-palette-render.txt)
и [диагональная панель](qa/brand-2-0-1/clip-modular.png) проверяют только UI.
[Установка 60](qa/brand-2-0-1/final-upgrade.json) сохранила даты Android/TV
и паузу City lights 0:03; [Dark](qa/brand-2-0-1/final60-phone-dark.png),
[Light](qa/brand-2-0-1/final60-phone-light.png) и TV launcher сняты с финальным APK.
