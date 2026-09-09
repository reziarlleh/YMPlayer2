# Анализ YMPlayer 1.x

Дата: 2026-09-09. Статус: первичный аудит исходников завершён. Это анализ
структуры и поведения по коду, а не новый прогон приложения или API.

## Зафиксированный reference

- Репозиторий: https://github.com/reziarlleh/YMPlayer.
- Локальный reference: `D:/_codex/YaPlay`.
- HEAD: `7d118b4e3ec817b706748ae8d129fdc00f3a0146`, 2026-09-08.
- По `app/build.gradle`: versionName `1.1.0`, versionCode `114`, applicationId
  `dev.petrov.yaplay`; minSdk 29, compileSdk/targetSdk 35, Java 17.
- Один Android-модуль `app`. UI написан на Java и Android Views.
- Рабочее дерево изначально содержит пользовательскую правку `.codex/config.toml`,
  неотслеживаемую прошивку и три каталога `artwork/concept-v2*`.
  Они не использованы как реализация приложения и не изменялись.
- На момент первичного аудита 2.x содержал только документы. После аудита
  создан нативный M1; актуальная сборка описана в [M1_VERIFICATION.md](M1_VERIFICATION.md).

RepoWise CLI использован для контекста связанных классов. Индекс указывал тот же
HEAD, но одна карточка кэша была помечена устаревшей; выводы проверены по текущему
исходному коду. MCP этой задачи ограничен каталогом 2.x, поэтому для reference
использован CLI. Оценки здоровья индекса не трактуются как доказательство ошибок.
Обновление индекса reference через глобальный launcher завершилось сообщением
`RepoWise index is busy`; чужая блокировка не снималась. Актуальность указанных
методов проверена прямым чтением файлов. Индекс нового проекта 2.x обновлён успешно.

## Карта реализации

Пути в таблице относительны `app/src/main/java/dev/petrov/yaplay/` в reference.
Номера строк относятся к зафиксированному commit; количество строк — полный файл.

| ID | Источник | Подтверждённая ответственность |
| --- | --- | --- |
| S01 | `MainActivity.java`, 4778 строк; buildContent:272, buildLibraryPage:311, showSearchEntry:1648, startDeviceLogin:4081 | Плеер, библиотека, поиск, настройки, вход, диалоги, файловый браузер, фоновые операции |
| S02 | `player/YmpPlaybackService.java`, 1970 строк; onLoadChildren:433, selectSourceOnly:640, playAt:722, preparePlayer:755, saveCurrentSourceProgress:1693 | MediaBrowserService, системная MediaSession, Android MediaPlayer, очередь, восстановление, уведомления, предзагрузка |
| S03 | `player/YmpRepository.java`, 761 строк; loadMoreWave:61, search:215, syncFavoriteCache:393, openForPlayback, like:502, client:561 | Координация Яндекс API, локальной музыки, кэша и feedback |
| S04 | `ymusic/YandexMusicClient.java`, 1384 строки; requestDeviceCode:49, search:115, getMoreMyWave:357, getDirectUrl:626 | Device OAuth, API, HTTP, JSON/XML, модели сервиса, получение потока |
| S05 | `player/LocalPlaylistStore.java`, 1147 строк; storageRoots:59, delete:317 | SAF URI, выбранные папки, JSON в SharedPreferences, плейлисты и локальное избранное |
| S06 | `cache/YandexTrackCache.java`, 662 строки; removeLikedTrack:172; `CacheFileIntegrity`, `CachedMediaValidator` | Постоянный кэш избранного, временный playback cache, раздельная проверка аудио и обложки |
| S07 | `ClipWaveActivity.java`, 1914 строк; playbackGeneration:117, проверка поколения:1691; `ymusic/ClipWaveClient.java` | Отдельная волна клипов, Media3 ExoPlayer, предзагрузка и видеоинтерфейс |
| S08 | `ymusic/TokenStore.java`, prefs:36; `player/YmpSettings.java`, prefs:145 | Один набор токенов на приложение; общие настройки в SharedPreferences |
| S09 | `player/EmbeddedSideBarService.java`, onStartCommand:104, overlay:264; `NwdRebootProtocol.java` | Встроенный overlay, команды магнитолы; подтверждаемый reboot K4811 |
| S10 | `update/AppUpdateManager.java`, check:76, HTTPS:291, parse:489; `Diagnostics.java` | Проверка обновлений, резервный источник, размер/хэш; журнал и экспорт диагностики |
| S11 | `AppStatusBar.java`, `SafeWindow.java`, `DeviceUi.java`, `PlayerPageLayout.java` | Статус и журнал, insets, ввод, адаптивная геометрия |

Открываемые источники:
[UI](https://github.com/reziarlleh/YMPlayer/blob/7d118b4e3ec817b706748ae8d129fdc00f3a0146/app/src/main/java/dev/petrov/yaplay/MainActivity.java),
[аудиосервис](https://github.com/reziarlleh/YMPlayer/blob/7d118b4e3ec817b706748ae8d129fdc00f3a0146/app/src/main/java/dev/petrov/yaplay/player/YmpPlaybackService.java),
[repository](https://github.com/reziarlleh/YMPlayer/blob/7d118b4e3ec817b706748ae8d129fdc00f3a0146/app/src/main/java/dev/petrov/yaplay/player/YmpRepository.java),
[API](https://github.com/reziarlleh/YMPlayer/blob/7d118b4e3ec817b706748ae8d129fdc00f3a0146/app/src/main/java/dev/petrov/yaplay/ymusic/YandexMusicClient.java),
[локальные плейлисты](https://github.com/reziarlleh/YMPlayer/blob/7d118b4e3ec817b706748ae8d129fdc00f3a0146/app/src/main/java/dev/petrov/yaplay/player/LocalPlaylistStore.java).

## Playback pipeline и данные

Аудио: действие UI/MediaSession → YmpPlaybackService → YmpRepository → локальный
SAF-дескриптор либо YandexTrackCache + YandexMusicClient → ParcelFileDescriptor →
Android MediaPlayer. Сервис обновляет MediaSession, уведомление и broadcast UI.
Аудио 1.x использует MediaPlayer; наличие Media3 в Gradle относится прежде всего
к отдельному видеопути. Нельзя считать аудио уже перенесённым на ExoPlayer.

Волна хранит session/batch/track identifiers, продолжает очередь и отправляет
feedback; repository содержит повторные запросы и fallback. Эти правила нужно
сначала описать поведенческими тестами. Замена транспорта не должна менять
порядок feedback, пропуски недоступных треков и восстановление позиции.

Выбор источника отдельно от Play сохраняет прогресс, останавливает плеер и
очищает текущую очередь. Просмотр каталога в 2.x должен быть отдельным действием:
навигация по библиотеке сама по себе не меняет источник воспроизведения.

Локальные треки также представлены `YandexMusicClient.Track`. Их URI и признаки
источника не должны превращать provider DTO в общую доменную модель 2.x.
Авторизация хранится в едином TokenStore, локальные плейлисты — JSON в
SharedPreferences. Это не готовый каталог с профилями, поисковым индексом и
постраничной выдачей.

## Риски, которые не переносим автоматически

| Наблюдение по коду | Влияние на 2.x | Решение |
| --- | --- | --- |
| S01 объединяет экраны, OAuth, файлы, API и настройки | Изменения сценария затрагивают большой UI-класс | Shell + feature state + явные use cases |
| S03 объединяет источники; его публичные методы широко synchronized | Долгая синхронизация может конкурировать с запросами того же экземпляра | Раздельные репозитории и ограниченные очереди фоновых работ |
| S02 одновременно владеет transport, queue, storage и платформенной сессией | Трудно тестировать порядок событий без Android | Чистая модель очереди, PlaybackCoordinator и backend adapter |
| В S02 playAt запускает Thread; callback preparePlayer не проверяет поколение запроса | Риск позднего результата после смены трека/источника | Session generation, отмена, закрытие устаревшего дескриптора |
| В S07 защита через playbackGeneration уже есть | Полезный проверяемый подход, но не повод копировать Activity | Зафиксировать аналогичный инвариант на границе playback |
| Provider-модели проходят через локальные файлы и UI | Яндекс определяет форму всего приложения | Source-qualified MediaId и доменные модели |
| S05 перечитывает/записывает JSON, имеет лимит обхода 5000 треков | Не является основой полноценной большой медиатеки | Индекс каталога, страничные запросы и инкрементальная индексация |
| Один TokenStore и общий кэш | Нет требуемой изоляции профилей | ProfileId/AccountId во всех аккаунтных операциях и ключах хранения |
| В OAuth-клиенте есть встроенные client credentials | Нельзя автоматически считать их конфигурацией нового продукта | Проверить допустимый способ авторизации до переноса; значения не дублировать |
| Search и artist tracks ограничены page=0 | Неполная выдача не должна выглядеть полной | Пагинация, явное состояние доступности и capabilities |
| Release 1.x использует историческую debug signing configuration | Необоснованное наследование схемы выпуска | Отдельно зафиксировать подпись 2.x до его распространения |

Пункты о конкуренции — риски, выявленные при чтении, а не воспроизведённые
дефекты 1.x. Исправления reference в рамках этого аудита не выполняются.

## Проверки и границы доказательств

В `app/src/test/java` найдены шесть тестовых классов: AdaptiveLayoutTest,
AppStatusBarTest, HeadUnitRebootActivityTest, NwdRebootProtocolTest,
CachedMediaValidatorTest, YandexTrackCacheTest. Есть содержательные проверки
повреждённых загрузок, независимого ремонта аудио/обложек, insets и reboot-only
контракта. Отдельных тестовых классов очереди/волны/переключения аккаунта нет.

Тесты в этом цикле не запускались: приложение не менялось, выполнялся анализ.
Авторизованные API-запросы, установка, проигрывание и устройство не проверялись.
Актуальный PROJECT_AUDIT.md 1.x сообщает о пользовательском подтверждении reboot
K4811 в beta.11; это историческое подтверждение, а не новый результат этого аудита.
Оно уточняет более старые заметки памяти, где проверка ещё ожидалась.

Профили, полная медиатека и PRISM отмечены в ROADMAP.md как согласованное будущее
направление. Утверждение об «отложенной 2.0» относится к старому плану 1.x;
нынешний запрос начинает отдельный проект 2.x.
