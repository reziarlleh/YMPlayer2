# Карта миграции

Обновлено: 2026-09-28. M1/M2 завершены в своих границах; в M3.1 реализованы основа скинов и ручная очередь,
в M3.2 — локальные плейлисты и избранное по профилям; в M3.3 — обложки, иерархия
«Назад» и восстановление недоступных ссылок очереди; [проверки build6](M3_3_VERIFICATION.md).
`analysis` означает выполненный первичный
анализ, но не готовую поведенческую спецификацию каждой внутренней ветки.
Текущая [сверка плана и фактов](PROJECT_AUDIT_2026-09-28.md) уточняет приёмку клипов
и границы M11. Ссылки на Fxx: [FEATURE_INVENTORY.md](FEATURE_INVENTORY.md), Sxx:
[LEGACY_1X_ANALYSIS.md](LEGACY_1X_ANALYSIS.md).

| Функции | Источник | Модуль 2.x | Тип | Статус | Проверка перед done |
| --- | --- | --- | --- | --- | --- |
| F19, новый shell | S01/S11 только сценарии | designsystem, feature:shell | redesign | M3.3 local; [UI/build30](UI_LANDSCAPE_RAIL_VERIFICATION.md), [build31](M9_3_VERIFICATION.md) | Иерархия «Назад», двойной выход, детали/диалоги, шрифт 200% и короткое окно проверены. Левое меню в альбомной ориентации едино для всех разделов и сохраняет подписи при 200%; облачные экраны отдельно |
| F02 | ROADMAP, S08 как ограничение | core/auth + provider/yandex | new | M4 implementation | Отдельные сессии/ключи/device ID, отмена старых работ, guest local mode; изоляция будущих музыкальных запросов — M5 |
| F01 | S01/S04/S08 | core/auth + provider/yandex + shell | migration/refactor | M4.1 verification | Повторная сверка с 1.x: OAuth сохраняется до account/status, возвращены HTTP-заголовки и тайм-ауты, код переживает сетевой сбой. [Проверки](M4_1_VERIFICATION.md); живой вход подтверждается отдельно |
| F16 | ROADMAP; S05 как reference | library + local index + playback + shell | new | M2/M5 частично; [M11.1](M11_1_VERIFICATION.md) — ограниченные запросы; [M11.2/build44](M11_2_VERIFICATION.md) — SQLite; [M11.3/build45](M11_3_VERIFICATION.md) — адресные коллекции; [M11.4/build46](M11_4_VERIFICATION.md) — компактный checkpoint; [M11.5/build47](M11_5_VERIFICATION.md) — окно Media3; [порядок ID и UI-страницы после build47](M11_INDEXED_ORDER_PROGRESS.md); полный M11 открыт | Локальный/USB-индекс мигрирует JSON с сохранением ID и недоступных ссылок; экран и окно выбора читают порции по 80. После build47 экран не получает весь каталог, очередь UI держит до пяти страниц и получает локальные метаданные из SQLite; индекс v1→v2 сохраняет данные; новый APK не опубликован. Библиотека и логическая очередь плеера ещё содержат полный каталог; порядок ID пока не управляет аудио; shuffle/повтор всей очереди загружают полный список Media3. Объединение с Яндексом и большой каталог открыты |
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
| F18 | S07 | provider/yandex/YandexClipApi + feature:clips | migration/refactor | [M9.1](M9_1_VERIFICATION.md) протокол; [M9.2/build29](M9_2_VERIFICATION.md) видео; [M9.3/build31](M9_3_VERIFICATION.md) поворот и инфо; [M9.4/build32](M9_4_VERIFICATION.md) оформление; [M9.5/build33](M9_5_VERIFICATION.md) предзагрузка и новая сессия | Сессия, очередь, feedback, выбор HLS/DASH/preview и граница OAuth перенесены; отдельный видеоплеер, Back, пауза аудио, сохранение сессии при повороте, сведения о следующем клипе, буфер начала следующего потока и перезапуск пустой очереди реализованы. Владелец подтвердил базовое воспроизведение, после build31 назвал меню и видеоплеер нормальными, выбрал и одобрил диагональную панель; длительная очередь и HLS/DASH-предзагрузка ждут приёмки build33 |
| F20 | S02 | playback/android MediaLibrarySession | migration/refactor | M8.1–M8.2/build22–23, эмуляторы; [M8.4](M8_4_VERIFICATION.md) boot emulator; CWG открыт | Media3 и platform MediaBrowser публикуют два источника 1.x; запуск и защита внешних URI проверены. MediaButtonReceiver запускает остановленную службу; восстановление после перезагрузки Android/TV эмуляторов проверено. Физические кнопки/boot/CWG ещё открыты. [Браузер](M8_1_VERIFICATION.md), [кнопки и фокус](M8_2_VERIFICATION.md) |
| F21/F22 | S09/ROADMAP | headunit/sidebar + shell/SideBarAccess | migration/refactor | [M10.1/build34](M10_1_VERIFICATION.md), [M10.2/build36](M10_2_VERIFICATION.md), [M10.3/build37](M10_3_VERIFICATION.md), [M10.4/build38](M10_4_VERIFICATION.md), [M10.5/build43](M10_5_VERIFICATION.md), Android 15 | Встроенное окно и разрешение реализованы. Зоны захвата невидимы, находятся под IME и пересчитываются при повороте; внешний контур со скосами 45° содержит круглые кнопки. Девять команд выбираются отдельно; сон и перезагрузка стоят перед обязательным сворачиванием, пустой набор выключает SideBar. K4811 volume/mute/Home/Back/меню/сон используют NWD-команды, Play/Pause — системную медиакоманду. Перезагрузка переносит подтверждение, однократный Binder transaction 0x1c/type 2 и обработку неизвестного результата из рабочего сценария 1.x. Владелец подтвердил его на K4811 в 1.x; физическая приёмка переноса в 2.x открыта; EQ/DSP относится к главному плееру, TS18-эвристика 1.x не переносится |
| F23 | S10/S11 | app/DiagnosticsJournal + shell/DiagnosticsScreen | migration/refactor | M8.3/build24, emulator verification | Фиксированные коды без секретов, ограничение размера, просмотр/очистка/экспорт Downloads. [Решение](DECISIONS/ADR-021-diagnostics.md), [проверки](M8_3_VERIFICATION.md); физическая приёмка отдельно |
| F24 | S10 | updater/android + app/UpdateCoordinator + shell/UpdateAccess | migration/refactor | [M12.1/build40](M12_1_VERIFICATION.md), [M12.2/build41–42](M12_2_VERIFICATION.md), [M12.3](M12_3_VERIFICATION.md), эмулятор Android 15; личная приёмка открыта | Собственный пакет/канал/манифест 2.x, GitHub → jsDelivr, размер/SHA-256/подпись APK, сквозной Build и системная установка. Обновления 40→41 с GitHub и 41→42 с jsDelivr прошли через UI без сброса данных; автопредложение проверено с подставленным манифестом. CDN может запаздывать; реальное предложение, сеть владельца и миграции открыты |

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
