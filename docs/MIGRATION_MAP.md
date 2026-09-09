# Карта миграции

Дата: 2026-09-09. Код ещё не переносился. `analysis` означает выполненный первичный
анализ, но не готовую поведенческую спецификацию каждой внутренней ветки.
Ссылки на Fxx: [FEATURE_INVENTORY.md](FEATURE_INVENTORY.md), Sxx:
[LEGACY_1X_ANALYSIS.md](LEGACY_1X_ANALYSIS.md).

| Функции | Источник | Модуль 2.x | Тип | Статус | Проверка перед done |
| --- | --- | --- | --- | --- | --- |
| F19, новый shell | S01/S11 только сценарии | designsystem, feature:shell | redesign | M1 prototype | Native layout/input: M1_VERIFICATION.md; реальная функциональность отдельно |
| F02 | ROADMAP, S08 как ограничение | profiles/auth | new | design | Account scope, отмена старых работ, guest local mode |
| F01 | S01/S04/S08 | profiles + provider/yandex | migration/refactor | analysis | Device code, expiry/cancel, изоляция токенов |
| F16/F17 | ROADMAP; S05 как reference | library + local index | new | design | Пагинация, USB unavailable, инкрементальный индекс |
| F04/F05/F06 | S02 | playback + Android adapter | migration/refactor | analysis | Порядок команд, stale callback, seek/restore/shuffle |
| F03 | S03/S04 | provider/yandex + playback | migration/refactor | analysis | Session/batch/feedback, continuation, retry/prefetch |
| F07/F08/F09 | S03/S06/S08 | favorites sync + cache | migration/refactor | analysis | Сохранить существующие integrity cases; unlike во время sync |
| F10/F12 | S01/S03/S04 | library/search + yandex | migration/refactor | analysis | Подтверждённые операции, partial pages, API errors |
| F11/F25 | ROADMAP | provider capability extensions | new | planned | Доступность API ещё не подтверждена |
| F13/F14/F15 | S05 | local source + library | migration/refactor | analysis | Отозванный SAF, USB, отсутствие удаления файлов |
| F18 | S07 | clips | migration/refactor | analysis | Взаимное исключение аудио/видео, prefetch, Back |
| F20 | S02 | Android media session | migration/refactor | analysis | CWG/media buttons/artwork и фон на целевом устройстве |
| F21/F22 | S09/ROADMAP | headunit/sidebar | migration/refactor | analysis | Overlay permission, команды K4811, состав кнопок |
| F23 | S10/S11 | diagnostics + UI status | migration/refactor | analysis | Журнал по запросу, отсутствие секретов, длинный текст |
| F24 | S10 | distribution/update | redesign | design | Только 2.x, beta/stable, Build, hash/signature, сохранение данных |

## Порядок

1. Выбор UI → shell на mock data → проверка компоновки и ввода.
2. Контракты, профильная модель и локальный каталог; вертикальный сценарий local playback.
3. Авторизация → Яндекс-каталог/поиск → волна/feedback → likes/cache.
4. MediaSession/CWG и восстановление; профили проверяются вместе с сетевыми работами.
5. Клипы, SideBar/K4811 и настройки возможностей как отдельные адаптеры.
6. Сквозная регрессия, независимый updater, итоговый APK и приёмка 2.0.0.

Перед каждым переносом: inputs/outputs/side effects → поведенческие примеры →
новый контракт → реализация → подходящие проверки → сравнение с reference →
обновление этой таблицы. Зависимости могут уточнить порядок, но не отменяют проверки.

Полная матрица функций — направление продукта. Состав первого стабильного выпуска
предложен в [PROJECT_PLAN.md](PROJECT_PLAN.md); функция считается перенесённой
только после реализации и проверки, а не после появления её названия в UI.

M1: профили, каталог и playback реализованы только демоадаптерами. Наличие таких
экранов не переводит F02/F04/F16/F17 в статус перенесённых функций. Код 1.x
не копировался; следующий этап — ограниченный сценарий local playback.
