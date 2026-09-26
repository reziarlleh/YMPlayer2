# Карта миграции

Обновлено: 2026-09-25. M1/M2 завершены в своих границах; в M3.1 реализованы основа скинов и ручная очередь,
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
| F16 | ROADMAP; S05 как reference | library + local index + shell | new | M2/M5 частично; полный объём M11 | Разделы UI и онлайн-страницы есть; единый индекс метаданных, постраничные локальные запросы, источник/доступность и большой USB-каталог ещё открыты |
| F17 | ROADMAP; S05 как reference | library + shell | new | после 2.0.0 | Расширенная история, недавно добавленное и массовые действия не включены в приёмку 2.0.0 |
| F04/F05/F06 | S02 | playback + Android adapter | migration/refactor | M3.3 local | Play/pause/stop/seek/skip, repeat/shuffle, ручная очередь и checkpoints; недоступные ссылки сохраняются, возврат текущего трека на паузе. Native Media3 и фон проверены; физический USB и облачные сценарии отдельно |
| F03 | S03/S04 | core/MyWave + provider/yandex + playback | migration/refactor | M6 verification | Session/batch/feedback, продолжение, retry и следующая рекомендация; пауза, профиль, восстановление. [Правила](DECISIONS/ADR-011-wave-and-taste.md) |
| F07 | S03/S06 + запрос владельца | core/MusicTaste + provider/yandex + shell | migration/refactor | M6 / M7.1 verification | Раздельные реакции трека/исполнителя, любимые альбомы; unlike не является block. Постоянный кэш подключён в M7.1 только для liked tracks |
| F08, офлайн F07 | S03/S06 | core/OfflineMusic + library:offline + app/OfflineSyncService | migration/refactor | M7.1 emulator / public API verified | Независимый ремонт аудио/обложки, отмена, unlike во время sync, profile/account scope, Media3 без сети; [перенос](DECISIONS/ADR-015-liked-offline-sync.md), [проверки](M7_1_VERIFICATION.md). Личная коллекция ожидает приёмки |
| F09 | S08/S04 | core/AudioQualityPreferences + provider:yandex + app + shell | migration/refactor | M7.2/build17, эмуляторы; личные битрейты открыты | Потоковая настройка охватывает «Мою волну» и обычные онлайн-треки; кэш «Мне нравится» имеет отдельную. Готовые файлы не меняются. [Перенос](DECISIONS/ADR-016-audio-quality.md), [проверки](M7_2_VERIFICATION.md) |
| F10/F12 | S01/S03/S04 | core/OnlineMusic + provider/yandex + playback + shell | migration/refactor | M5 verification | Чтение/запуск списков, поиск четырёх типов, детали и страницы; изоляция запросов и очередь. [Проверки](M5_VERIFICATION.md). Создание, append и удаление своих облачных списков F10 — [M7.3](M7_3_VERIFICATION.md), core/CloudPlaylists + YandexPlaylistApi; серверная приёмка отдельно |
| F11 | Запрос владельца + протокол клиента Яндекса, не реализация 1.x | core/CloudPlaylists + YandexPlaylistApi + shell | new | M7.4 verification | Переименование, удаление конкретного вхождения, перемещение одним diff; revision, сохранение повторов и перечитывание результата. [Решение](DECISIONS/ADR-018-cloud-playlist-editor.md), [проверки](M7_4_VERIFICATION.md); реальную запись принимает владелец |
| F25 | ROADMAP + запрос владельца | provider capability extensions | new | M6 partial verification | Рекомендованные плейлисты и любимые исполнители/альбомы реализованы; реальный аккаунт принимает владелец. Волна по треку/артисту и mood остаются будущими возможностями |
| F13/F14/F15 | S05 | local source + library | migration/refactor | M3.3 local | SAF, rescan, unavailable; собственные плейлисты/избранное, встроенные обложки и ограниченный кэш без изменения оригиналов. Возврат источника проверен через DocumentsProvider; физический hot-plug отдельно |
| F18 | S07 | provider/yandex/YandexClipApi + feature:clips | migration/refactor | [M9.1](M9_1_VERIFICATION.md) протокол; [M9.2/build29](M9_2_VERIFICATION.md) видео, эмуляторы | Сессия, очередь, feedback, выбор HLS/DASH/preview и граница OAuth перенесены; отдельный видеоплеер, предварительный запрос URL, Back и пауза аудио реализованы. Предзагрузка видеобайтов, живое видео/длительная очередь и физическая приёмка открыты |
| F20 | S02 | playback/android MediaLibrarySession | migration/refactor | M8.1–M8.2/build22–23, эмуляторы; [M8.4](M8_4_VERIFICATION.md) boot emulator; CWG открыт | Media3 и platform MediaBrowser публикуют два источника 1.x; запуск и защита внешних URI проверены. MediaButtonReceiver запускает остановленную службу; восстановление после перезагрузки Android/TV эмуляторов проверено. Физические кнопки/boot/CWG ещё открыты. [Браузер](M8_1_VERIFICATION.md), [кнопки и фокус](M8_2_VERIFICATION.md) |
| F21/F22 | S09/ROADMAP | headunit/sidebar | migration/refactor | analysis | Overlay permission, команды K4811, состав кнопок |
| F23 | S10/S11 | app/DiagnosticsJournal + shell/DiagnosticsScreen | migration/refactor | M8.3/build24, emulator verification | Фиксированные коды без секретов, ограничение размера, просмотр/очистка/экспорт Downloads. [Решение](DECISIONS/ADR-021-diagnostics.md), [проверки](M8_3_VERIFICATION.md); физическая приёмка отдельно |
| F24 | S10 | distribution/update | redesign | design | Только 2.x, beta/stable, Build, hash/signature, сохранение данных |

## Порядок

Подробный порядок M1–M13 и критерии завершения: [ROADMAP.md](ROADMAP.md).

M4: успешный вход подтверждён владельцем на build8. M5/build9: каталог и поиск
подтверждены владельцем; перенос аудиопротокола исправлен в build10/M5.1,
2026-09-12 владелец подтвердил воспроизведение.
M6 добавил волну, рекомендации и отдельные likes/dislikes. M7.1–M7.6 реализованы:
офлайн-кэш только «Мне нравится» (без загрузки любимых альбомов/исполнителей),
качество потока/кэша, облачные операции и редактор. M8.1–M8.2 добавили браузер
двух источников, медиакнопки и восстановление остановленной службы. Открыты
личная приёмка M7 и аппаратная M8; далее диагностика/CWG → клипы →
SideBar/K4811 → полный каталог F16 → выпуск 2.0.0 → скины 2.1.0.

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
