# Карта миграции

Дата: 2026-09-10. M1/M2 завершены в своих границах; в M3.1 реализованы основа скинов и ручная очередь,
в M3.2 — локальные плейлисты и избранное по профилям; в M3.3 — обложки, иерархия
«Назад» и восстановление недоступных ссылок очереди; [проверки build6](M3_3_VERIFICATION.md).
`analysis` означает выполненный первичный
анализ, но не готовую поведенческую спецификацию каждой внутренней ветки.
Ссылки на Fxx: [FEATURE_INVENTORY.md](FEATURE_INVENTORY.md), Sxx:
[LEGACY_1X_ANALYSIS.md](LEGACY_1X_ANALYSIS.md).

| Функции | Источник | Модуль 2.x | Тип | Статус | Проверка перед done |
| --- | --- | --- | --- | --- | --- |
| F19, новый shell | S01/S11 только сценарии | designsystem, feature:shell | redesign | M3.3 local | Иерархия «Назад», двойной выход, детали/диалоги, шрифт 200% и короткое окно проверены; облачные экраны отдельно |
| F02 | ROADMAP, S08 как ограничение | profiles/auth | new | design | Account scope, отмена старых работ, guest local mode |
| F01 | S01/S04/S08 | profiles + provider/yandex | migration/refactor | analysis | Device code, expiry/cancel, изоляция токенов |
| F16/F17 | ROADMAP; S05 как reference | library + local index | new | design | Пагинация, USB unavailable, инкрементальный индекс |
| F04/F05/F06 | S02 | playback + Android adapter | migration/refactor | M3.3 local | Play/pause/stop/seek/skip, repeat/shuffle, ручная очередь и checkpoints; недоступные ссылки сохраняются, возврат текущего трека на паузе. Native Media3 и фон проверены; физический USB и облачные сценарии отдельно |
| F03 | S03/S04 | provider/yandex + playback | migration/refactor | analysis | Session/batch/feedback, continuation, retry/prefetch |
| F07/F08/F09 | S03/S06/S08 | favorites sync + cache | migration/refactor | analysis | Сохранить существующие integrity cases; unlike во время sync |
| F10/F12 | S01/S03/S04 | library/search + yandex | migration/refactor | analysis | Подтверждённые операции, partial pages, API errors |
| F11/F25 | ROADMAP | provider capability extensions | new | planned | Доступность API ещё не подтверждена |
| F13/F14/F15 | S05 | local source + library | migration/refactor | M3.3 local | SAF, rescan, unavailable; собственные плейлисты/избранное, встроенные обложки и ограниченный кэш без изменения оригиналов. Возврат источника проверен через DocumentsProvider; физический hot-plug отдельно |
| F18 | S07 | clips | migration/refactor | analysis | Взаимное исключение аудио/видео, prefetch, Back |
| F20 | S02 | Android media session | migration/refactor | M2 basic session | MediaSession и медиакнопки проверяются в M2; CWG/MediaBrowser/целевое устройство — M8 |
| F21/F22 | S09/ROADMAP | headunit/sidebar | migration/refactor | analysis | Overlay permission, команды K4811, состав кнопок |
| F23 | S10/S11 | diagnostics + UI status | migration/refactor | analysis | Журнал по запросу, отсутствие секретов, длинный текст |
| F24 | S10 | distribution/update | redesign | design | Только 2.x, beta/stable, Build, hash/signature, сохранение данных |

## Порядок

Подробный порядок M1–M12 и критерии завершения: [ROADMAP.md](ROADMAP.md).

После M3.3: аккаунты Яндекса внутри профиля (M4).
Далее каталог/поиск Яндекса → волна → likes/cache →
системная интеграция/CWG → клипы → SideBar/K4811 → пользовательские скины → выпуск.

Перед каждым переносом: inputs/outputs/side effects → поведенческие примеры →
новый контракт → реализация → подходящие проверки → сравнение с reference →
обновление этой таблицы. Зависимости могут уточнить порядок, но не отменяют проверки.

Полная матрица функций — направление продукта. Состав первого стабильного выпуска
предложен в [PROJECT_PLAN.md](PROJECT_PLAN.md); функция считается перенесённой
только после реализации и проверки, а не после появления её названия в UI.

M1: профили, каталог и playback были демоадаптерами. В M2 production использует
SAF и ExoPlayer; debug DemoActivity сохраняет UI-регрессии на фикстурах. Полноценные
облачные профили F02, история F17 и весь объём F16 пока не реализованы. Сам факт
наличия экранов не переводит эти функции в статус готовых.
