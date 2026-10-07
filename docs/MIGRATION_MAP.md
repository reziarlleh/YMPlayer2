# Карта переноса и модулей

Стабильный срез: **2.5.1-build98** / API28. Настройки/источники волн и режимы
из beta81/82 включены в stable; [контрольный отчёт](RELEASE_2_4_0_VERIFICATION.md).
Patch84 ограничивает кнопку/окно настроек активной My Wave; [проверки](PATCH_2_4_1_VERIFICATION.md).

Патч2.5.1 сверяет быстрый старт с YmpPlaybackService/YmpRepository1.x:
первый трек сразу, следующий в фоне. Устранено ожидание общего Mutex полной
проверки постоянного кэша. OfflineMusic использует быстрый catalog, проверка
выбранного файла остаётся в LikedFileStore.audio. ClipActivity отделяет явный
вход с Play от восстановления Pause; VideoSize применяется при первом кадре.
[Отчёт](PATCH_2_5_1_VERIFICATION.md). Репозиторий1.x не изменялся.
Путь данных/модули сохраняются; адаптации API28 не изменяют алгоритмы API29+.
Stable2.5.0-build97 (раздел реализован в87): отдельный раздел FM Радио по выбранному варианту1,
через `core/RadioController`, `provider:yandex/YandexRadioApi`,
`playback:android/AndroidRadio` и `feature:shell/RadioScreen`. Это новый FM API,
не копия трековой очереди/rotor1.x. Авторизация использует прежний AccountAuth;
эфир использует общий AudioService. [ADR-042](ADR_042_FM_RADIO_SHARED_OUTPUT.md),
[проверки](RADIO_2_5_VERIFICATION.md). Реальная запись коллекции требует приёмки.
В beta92 LaunchStateStore (`playback:android`) хранит последний выход и play intent,
ClipCheckpointStore (`feature:clips`) — текущий клип и позицию. `app` координирует
foreground-восстановление и хранит навигацию; RadioController сохраняет вкладку/
поиск/фильтр по профилю. Существующий музыкальный checkpoint переиспользован.
Старые строки о восстановлении на паузе относятся к указанным историческим build;
новое требование владельца продолжает ранее играющий источник при открытии.
[Результат и границы](SESSION_RECOVERY_2_5_VERIFICATION.md).
В beta93 core исправляет конкурентные ответы справочников и cursor новой
выборки, provider читает описание станции как простой текст, shell показывает
сетки/карусели и модальную карточку с сохранением места/фокуса. Аудиоадаптер
не менялся. [Проверки](RADIO_CATALOG_2_5_VERIFICATION.md).
Stable97: LocalCatalogIndex schema4 сохраняет ID/added_at и перечитывает
только кандидатов прежних заполнителей; реальные имена не заменяются.
Shell LocalTagLabels переводит отсутствующие поля. Core Radio/OnlineMusic
исправляют B-015/B-016. Старый MessageScreen клипов удалён; дизайн-подложка
осталась как FallbackArtwork. [Аудит](AUDIT_2_5_RELEASE.md).
Таблица фиксирует происхождение и проверку алгоритмов. Номер старого build
в строке — момент переноса, а не текущая версия приложения.
Fxx: [карта функций](FEATURE_INVENTORY.md); Sxx: [reference1.x](LEGACY_1X_ANALYSIS.md).

Позднейшие изменения: все три сценария F17 завершены в75–77,
диагностика F23 дополнена в78, навигация F19 исправлена в79.
[Последнее исправление](PATCH_2_3_4_VERIFICATION.md), [план2.3](PLAN_2_3.md).
Подробные фактические границы модулей: [MODULES](MODULES.md).
Для F20/F26 есть отдельное подтверждение самого CWG3.6.3-R2/API29 на
эмуляторе: логотип, эфирные метаданные и Pause/Play в signed87;
[CWG-проверка](RADIO_CWG_VERIFICATION.md). Физическая приёмка K4811 остаётся открытой.

| Функции | Источник | Модуль 2.x | Тип | Статус | Проверка перед done |
| --- | --- | --- | --- | --- | --- |
| F26 | Уточнения владельца и актуальный FM frontend/API | core + provider/yandex + playback/android + feature/shell | new | Полный раздел в ручной beta87, общий AudioService и текущий AccountAuth | [Проверки87](RADIO_2_5_VERIFICATION.md): API/эфир/коллекция на стенде, signed API28/TV29; реальный station POST ожидает приёмки |
| F19, базовая айдентика | Уточнение владельца 2026-10-01 | designsystem + launcher | redesign | Утверждены монограмма №4/r5 и палитра №1 «Оксид», внедрены в build60; широкий YM + Player2 и TV banner опубликованы в build61 | [Айдентика/палитры build60](BRAND_2_0_1_VERIFICATION.md), [широкий знак/build61](WIDE_BRAND_2_0_1_VERIFICATION.md); ресурсы и TV launcher проверены, импорт внешних скинов — M13 |
| F19, новый shell | S01/S11 только сценарии | designsystem, feature:shell | redesign | M3.3 local; [UI/build30](UI_LANDSCAPE_RAIL_VERIFICATION.md), [build31](M9_3_VERIFICATION.md) | Иерархия «Назад», двойной выход, детали/диалоги, шрифт 200% и короткое окно проверены. Левое меню в альбомной ориентации едино для всех разделов и сохраняет подписи при 200%; облачные экраны отдельно |
| F02 | ROADMAP, S08 как ограничение | core/auth + provider/yandex + playback | new | Реализована независимая авторизация профилей 2.x; [M12.7](M12_7_VERIFICATION.md) проверяет медленное восстановление | Отдельные сессии/ключи/device ID, отмена старых запросов, guest local mode. Трек старого профиля не публикуется в новом при задержке IO, незавершённый restore сохраняет целевой checkpoint. Общий локальный каталог задан политикой; синхронизация самих профилей не входит в 2.0 |
| F01 | S01/S04/S08 | core/auth + provider/yandex + shell | migration/refactor | M4.1 verification | Повторная сверка с 1.x: OAuth сохраняется до account/status, возвращены HTTP-заголовки и тайм-ауты, код переживает сетевой сбой. [Проверки](M4_1_VERIFICATION.md); живой вход подтверждается отдельно |
| F16 | ROADMAP; S05 как reference | library + local index + playback + shell | new | Реализовано: локальный/USB SQLite и адресный доступ build44–54; [M11.12/build55](M11_12_VERIFICATION.md) объединяет локальные/USB и Яндекс-результаты в одном каталоге и поиске. Проверены 17 сценариев на Android 15, 16 при шрифте 200% и шесть на Android TV, включая D-pad. Подписанный APK сохраняет данные при обновлении 54→55; фоновый WAV, системные кнопки и восстановление на паузе проверены отдельно. | JSON→SQLite сохраняет ID; ручной порядок — ссылки, волна — отдельная сессия с подготовкой следующего трека. Перенос 54→55 сохраняет каталог и позицию. Кэш только «Мне нравится». Выпускная регрессия M12.7 завершена |
| F17 | Утверждённый PLAN_2_3; S05 как reference | library/local + core + shell | new | Все три сценария реализованы в75–77 | [История75](RELEASE_2_3_0_VERIFICATION.md), [недавно добавленное76](RELEASE_2_3_1_VERIFICATION.md), [массовые действия77](RELEASE_2_3_2_VERIFICATION.md); настройки/данные сохраняются, приёмка владельцем учитывается отдельно |
| Скины | Уточнение владельца / M13 | designsystem + shell | new | Опубликованы в64 | [Формат](SKIN_FORMAT.md), [импорт/preview/откат и проверки](M13_VERIFICATION.md) |
| Языки | Уточнение владельца / M14 | localization + app + shell | new | Auto и восемь языков опубликованы в72 | [Проверки](M14_ALL_LANGUAGES_VERIFICATION.md); музыкальные данные не переводятся, English guide готов |
| F04/F05/F06 | S02 | playback + Android adapter | migration/refactor | M3.3 local | Play/pause/stop/seek/skip, repeat/shuffle, ручная очередь и checkpoints; недоступные ссылки сохраняются, возврат текущего трека на паузе. Native Media3 и фон проверены; физический USB и облачные сценарии отдельно |
| F03 | S03/S04 | core/MyWave + provider/yandex + playback | migration/refactor | M6 verification | Session/batch/feedback, продолжение, retry и следующая рекомендация; пауза, профиль, восстановление. В build50 активная Media3-очередь сокращена до предыдущего/текущего/следующего трека; отдельная история исключения повторов остаётся. [Правила](DECISIONS/ADR-011-wave-and-taste.md) |
| F25, источники/режимы | Прямое уточнение владельца 2026-10-06 | core/PlaybackOrigin + provider/rotor + playback + shell | extension | **2.4.0-build83** (реализовано82) | [ADR-041](ADR_041_WAVE_SOURCES_AND_LIST_MODES.md), [проверки](WAVE_SOURCES_2_4_VERIFICATION.md): seed всех четырёх объектов, session/feedback, paused restore; 101 JVM/80 native в beta82, расширенная регрессия и signed-проверки83 — [отчёт](RELEASE_2_4_0_VERIFICATION.md); физические модели отдельно |
| F25, настройки волны | Прямое указание владельца 2026-10-06; современный API, а не готовый модуль1.x | core/WaveSettings + provider/YandexWaveApi + playback + shell | new | Beta81, stable83 | [ADR-040](ADR_040_WAVE_SETTINGS.md), [проверки](WAVE_SETTINGS_2_4_VERIFICATION.md); выбранные seeds/станция сохраняются, профиль/UID изолированы. Остальные источники/режимы реализованы82; при первом этапе81 не входили в задачу |
| F07 | S03/S06 + запрос владельца | core/MusicTaste + provider/yandex + shell | migration/refactor | M6 / M7.1 verification | Раздельные реакции трека/исполнителя, любимые альбомы; unlike не является block. Постоянный кэш подключён в M7.1 только для liked tracks |
| F08, офлайн F07 | S03/S06 | core/OfflineMusic + library:offline + app/OfflineSyncService | migration/refactor | M7.1 emulator / public API verified | Независимый ремонт аудио/обложки, отмена, unlike во время sync, profile/account scope, Media3 без сети; [перенос](DECISIONS/ADR-015-liked-offline-sync.md), [проверки](M7_1_VERIFICATION.md). Личная коллекция ожидает приёмки |
| F09 | S08/S04 | core/AudioQualityPreferences + provider:yandex + app + shell | migration/refactor | M7.2/build17, эмуляторы; личные битрейты открыты | Потоковая настройка охватывает «Мою волну» и обычные онлайн-треки; кэш «Мне нравится» имеет отдельную. Готовые файлы не меняются. [Перенос](DECISIONS/ADR-016-audio-quality.md), [проверки](M7_2_VERIFICATION.md) |
| F10/F12 | S01/S03/S04 | core/OnlineMusic + provider/yandex + playback + shell | migration/refactor | M5 verification | Чтение/запуск списков, поиск четырёх типов, детали и страницы; изоляция запросов и очередь. [Проверки](M5_VERIFICATION.md). Создание, append и удаление своих облачных списков F10 — [M7.3](M7_3_VERIFICATION.md), core/CloudPlaylists + YandexPlaylistApi; серверная приёмка отдельно |
| F11 | Запрос владельца + протокол клиента Яндекса, не реализация 1.x | core/CloudPlaylists + YandexPlaylistApi + shell | new | M7.4 verification | Переименование, удаление конкретного вхождения, перемещение одним diff; revision, сохранение повторов и перечитывание результата. [Решение](DECISIONS/ADR-018-cloud-playlist-editor.md), [проверки](M7_4_VERIFICATION.md); реальную запись принимает владелец |
| F25, прежний объём M6 | ROADMAP + запрос владельца | provider capability extensions | new | M6 partial verification | Рекомендованные плейлисты и любимые исполнители/альбомы реализованы; реальный аккаунт принимает владелец. Настройки настроения добавлены в beta81 (строка выше); волны по четырём объектам, режимы и переключатель реализованы в beta82; строка источников выше и [отчёт](WAVE_SOURCES_2_4_VERIFICATION.md) |
| F13/F14/F15 | S05 | local source + library | migration/refactor | M3.3 local | SAF, rescan, unavailable; собственные плейлисты/избранное, встроенные обложки и ограниченный кэш без изменения оригиналов. Возврат источника проверен через DocumentsProvider; физический hot-plug отдельно |
| F18 | S07 / ClipWaveActivity | provider/yandex + feature:clips | migration/refactor | M9.1–M9.5 реализованы; [M9.6](M9_6_VERIFICATION.md) проверяет сегменты и 60 естественных окончаний на Android 15/TV | Сохранён владелец rotor-сессии для feedback, один следующий медиаресурс, повторный start после пустого /next и история до 40. Собственные HLS/DASH читаются Media3 до и после перехода. API-фикстуры и локальные сегменты не выдаются за новую live-приёмку Яндекса |
| F20 | S02 | playback/android MediaLibrarySession | migration/refactor | M8.1–M8.2/build22–23, эмуляторы; [M8.4](M8_4_VERIFICATION.md) boot emulator; CWG открыт | Media3 и platform MediaBrowser публикуют два источника 1.x; запуск и защита внешних URI проверены. MediaButtonReceiver запускает остановленную службу; восстановление после перезагрузки Android/TV эмуляторов проверено. Физические кнопки/boot/CWG ещё открыты. [Браузер](M8_1_VERIFICATION.md), [кнопки и фокус](M8_2_VERIFICATION.md) |
| F21/F22 | S09/ROADMAP | headunit/sidebar + shell/SideBarAccess | migration/refactor | [M10.1/build34](M10_1_VERIFICATION.md), [M10.2/build36](M10_2_VERIFICATION.md), [M10.3/build37](M10_3_VERIFICATION.md), [M10.4/build38](M10_4_VERIFICATION.md), [M10.5/build43](M10_5_VERIFICATION.md), Android 15 | Встроенное окно и разрешение реализованы. Зоны захвата невидимы, находятся под IME и пересчитываются при повороте; внешний контур со скосами 45° содержит круглые кнопки. Девять команд выбираются отдельно; сон и перезагрузка стоят перед обязательным сворачиванием, пустой набор выключает SideBar. K4811 volume/mute/Home/Back/меню/сон используют NWD-команды, Play/Pause — системную медиакоманду. Перезагрузка переносит подтверждение, однократный Binder transaction 0x1c/type 2 и обработку неизвестного результата из рабочего сценария 1.x. Владелец подтвердил его на K4811 в 1.x; физическая приёмка переноса в 2.x открыта; EQ/DSP относится к главному плееру, TS18-эвристика 1.x не переносится |
| F23 | S10/S11 | app/DiagnosticsJournal + shell/DiagnosticsScreen | migration/refactor | M8.3/build24, emulator verification | Фиксированные коды без секретов, ограничение размера, просмотр/очистка/экспорт Downloads. В stable78 добавлены типы/стек последнего crash без сообщений и системные причины завершения: [ADR-038](ADR_038_CRASH_DIAGNOSTICS.md). [Решение](DECISIONS/ADR-021-diagnostics.md), [проверки](M8_3_VERIFICATION.md); физическая приёмка отдельно |
| F24 | S10 | updater/android + app/UpdateCoordinator + shell/UpdateAccess | migration/refactor | [M12.1/build40](M12_1_VERIFICATION.md), [M12.2/build41–42](M12_2_VERIFICATION.md), [M12.3](M12_3_VERIFICATION.md), [M12.4/build48](M12_4_VERIFICATION.md), [M12.5/build49](M12_5_VERIFICATION.md), эмулятор Android 15; личная приёмка открыта | Собственный пакет/канал/манифест 2.x, GitHub/jsDelivr/Gcore, размер/SHA-256/подпись APK, сквозной Build и системная установка. Обновления 40→41 с GitHub и 41→42 с jsDelivr прошли через UI без сброса данных; build47→48 показал реальное автопредложение и установился. Build49 включает выбор новейшего валидного манифеста; опубликованные APK трёх узлов совпали по SHA-256, а Gcore @main временно отставал на build48. Клиент build49 нашёл build50 и установил его через системный установщик ([M12.6](M12_6_VERIFICATION.md)). [M11.8/build51](M11_8_VERIFICATION.md) проверил установку 50→51 кнопкой резервной загрузки и сохранение тестового каталога/позиции. M12.7 завершил регрессию; M12.8 подтвердил 56→57 через резервный UI, сохранение данных/позиции и отдельный stable-манифест. Сеть владельца проверяется отдельно |

## Порядок переноса

Для каждой функции: изучить рабочие inputs/outputs/ошибки1.x → определить
контракт2.x → реализовать поведение → проверить реальные ответы и подходящие
сценарии → записать результаты, неверные подходы и остаточные ограничения.
Сохранить отдельные package, данные, профили и канал обновлений.

Облачная синхронизация профилей не реализована и не входит в текущий план.
История профиля, недавно добавленная музыка и массовые действия уже реализованы;
наличие экрана само по себе не служит доказательством работоспособности.
[Текущий статус и приёмка](PROJECT_STATUS.md) · [Журнал](WORK_JOURNAL.md).

Beta94 уточняет F18/F19: внешняя ClipActivity не заменяет Compose-раздел;
Back/Close возвращают прежний экран, disk return route переживает process death.
Алгоритмы клипов/аудио1.x не менялись. F26/FM использует только автоматическую
догрузку в каруселях/сетке, с явным Retry после ошибки.
[Реализация и проверки](CLIP_BACK_2_5_VERIFICATION.md).

Beta95 — уточнение F18/F19/F26 без переноса нового алгоритма1.x: общий порт
доступности и grace в core, default network adapter в app, сообщения в shell/
native clip overlay. Локальные/USB/офлайн функции от порта не зависят;
reconnect заменяет pending online запрос. [Проверки](INTERNET_2_5_VERIFICATION.md).

Stable97: ShellApp читает route через delegated derivedStateOf: навигация и Back
в одном кадре не используют прежний экран. [B-017](AUDIT_2_5_RELEASE.md).
