# Карта миграции

Дата: 2026-09-11. M1/M2 завершены в своих границах; в M3.1 реализованы основа скинов и ручная очередь,
в M3.2 — локальные плейлисты и избранное по профилям; в M3.3 — обложки, иерархия
«Назад» и восстановление недоступных ссылок очереди; [проверки build6](M3_3_VERIFICATION.md).
`analysis` означает выполненный первичный
анализ, но не готовую поведенческую спецификацию каждой внутренней ветки.
Ссылки на Fxx: [FEATURE_INVENTORY.md](FEATURE_INVENTORY.md), Sxx:
[LEGACY_1X_ANALYSIS.md](LEGACY_1X_ANALYSIS.md).

| Функции | Источник | Модуль 2.x | Тип | Статус | Проверка перед done |
| --- | --- | --- | --- | --- | --- |
| F19, новый shell | S01/S11 только сценарии | designsystem, feature:shell | redesign | M3.3 local | Иерархия «Назад», двойной выход, детали/диалоги, шрифт 200% и короткое окно проверены; облачные экраны отдельно |
| F02 | ROADMAP, S08 как ограничение | core/auth + provider/yandex | new | M4 implementation | Отдельные сессии/ключи/device ID, отмена старых работ, guest local mode; изоляция будущих музыкальных запросов — M5 |
| F01 | S01/S04/S08 | core/auth + provider/yandex + shell | migration/refactor | M4.1 verification | Повторная сверка с 1.x: OAuth сохраняется до account/status, возвращены HTTP-заголовки и тайм-ауты, код переживает сетевой сбой. [Проверки](M4_1_VERIFICATION.md); живой вход подтверждается отдельно |
| F16/F17 | ROADMAP; S05 как reference | library + local index | new | design | Пагинация, USB unavailable, инкрементальный индекс |
| F04/F05/F06 | S02 | playback + Android adapter | migration/refactor | M3.3 local | Play/pause/stop/seek/skip, repeat/shuffle, ручная очередь и checkpoints; недоступные ссылки сохраняются, возврат текущего трека на паузе. Native Media3 и фон проверены; физический USB и облачные сценарии отдельно |
| F03 | S03/S04 | core/MyWave + provider/yandex + playback | migration/refactor | M6 verification | Session/batch/feedback, продолжение, retry и следующая рекомендация; пауза, профиль, восстановление. [Правила](DECISIONS/ADR-011-wave-and-taste.md) |
| F07 | S03/S06 + запрос владельца | core/MusicTaste + provider/yandex + shell | migration/refactor | M6 verification | Раздельные реакции трека/исполнителя, любимые альбомы; unlike не является block. Постоянный кэш ещё не подключён |
| F08/F09, остаток F07 | S03/S06/S08 | favorites sync + cache | migration/refactor | planned M7 | Сохранить integrity cases; unlike во время sync; загружать только «Мне нравится», не любимые альбомы/дискографии |
| F10/F12 | S01/S03/S04 | core/OnlineMusic + provider/yandex + playback + shell | migration/refactor | M5 verification | Чтение/запуск списков, поиск четырёх типов, детали и страницы; изоляция запросов и очередь. [Проверки](M5_VERIFICATION.md). Изменения облачных плейлистов F10 — M7 |
| F11/F25 | ROADMAP + запрос владельца | provider capability extensions | new | M6 partial verification | Персональные плейлисты и любимые исполнители/альбомы реализованы; реальный аккаунт принимает владелец. Волна по треку/артисту и mood остаются будущими возможностями |
| F13/F14/F15 | S05 | local source + library | migration/refactor | M3.3 local | SAF, rescan, unavailable; собственные плейлисты/избранное, встроенные обложки и ограниченный кэш без изменения оригиналов. Возврат источника проверен через DocumentsProvider; физический hot-plug отдельно |
| F18 | S07 | clips | migration/refactor | analysis | Взаимное исключение аудио/видео, prefetch, Back |
| F20 | S02 | Android media session | migration/refactor | M2 basic session | MediaSession и медиакнопки проверяются в M2; CWG/MediaBrowser/целевое устройство — M8 |
| F21/F22 | S09/ROADMAP | headunit/sidebar | migration/refactor | analysis | Overlay permission, команды K4811, состав кнопок |
| F23 | S10/S11 | diagnostics + UI status | migration/refactor | analysis | Журнал по запросу, отсутствие секретов, длинный текст |
| F24 | S10 | distribution/update | redesign | design | Только 2.x, beta/stable, Build, hash/signature, сохранение данных |

## Порядок

Подробный порядок M1–M12 и критерии завершения: [ROADMAP.md](ROADMAP.md).

M4: успешный вход подтверждён владельцем на build8. M5/build9: каталог и поиск
подтверждены владельцем; перенос аудиопротокола исправлен в build10/M5.1,
2026-09-12 владелец подтвердил воспроизведение.
В текущем M6 добавляются волна, рекомендации и отдельные likes/dislikes;
любимые альбомы/исполнители не разворачиваются в треки будущего кэша.
Далее офлайн-кэш только «Мне нравится» →
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
