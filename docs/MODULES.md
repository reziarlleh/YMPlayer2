# Границы модулей

Дата: 2026-09-09. Это логическая карта; каталоги/Gradle-модули добавляются по мере
реализации. Общую шину событий, «универсальный repository» и пустые модули не создаём.

| Модуль | Ответственность | Допустимые зависимости |
| --- | --- | --- |
| app | Composition root, маршруты, manifest, lifecycle entry points | UI и конкретные adapters только для сборки зависимостей |
| core:model/contracts | MediaId, ProfileId, playback/library contracts, errors | Kotlin; без Android/provider DTO |
| ui:designsystem | Tokens, reusable controls, input/focus policy | Compose; без сети/storage |
| feature:player | Player/mini-player/queue UiState и UI | Core contracts, design system |
| feature:library/search | Каталог, детали, поиск, фильтры/selection | Library/search contracts, design system |
| feature:profiles | Профиль, вход, переключение, session UiState | Profile/auth contracts, design system |
| playback | Queue engine, coordinator, checkpoint use cases | Core; backend/session/storage interfaces |
| library | Запросы каталога, indexing policy, playlist use cases | Core и storage/source interfaces |
| provider:yandex | API mapping, auth flow, wave protocol, capabilities | Core + network adapter; никакого UI |
| infrastructure:android | Media3/session, SAF, Room, settings, secrets, diagnostics | Core, соответствующие Android libraries |
| feature:clips | Видео UI и clip use cases | Core + clip provider/playback contracts |
| integration:headunit/sidebar | Overlay, команды устройства, optional capability | Android; публичные playback/settings contracts |
| distribution:update | Манифест/артефакт своего продукта, версия, проверка | Network, package identity, storage; без legacy feed |

## Физический старт для первого beta-прототипа

Достаточно `:app`, `:core` (чистые модели/контракты), `:designsystem`, `:feature:shell`
(экраны на mock data). Это начальная упаковка, а не объединение будущей бизнес-логики.
Экранные пакеты внутри shell разделены; реальные функциональные модули выделяются
при подключении поведения. Ни provider, ни Room, ни playback service пока не нужны.

DemoPlaybackController реализует минимальный UI-контракт, но явно является
демонстрационной моделью без звука. Не делать fake implementation production fallback.
При добавлении настоящего playback заменить привязку в composition root.

Эти четыре модуля созданы в M1. ShellModel получает Catalog и PlaybackController
из MainActivity; ядро не импортирует Android. Подробнее о границах сохранения
состояния и выбранных версиях — [BUILDING.md](BUILDING.md) и [ADR-002](DECISIONS/ADR-002-native-m1.md).

## Проверка границ

В M2 добавлены `:library:local` (SAF, чтение метаданных, атомарный JSON-индекс)
и `:playback:android` (Media3, MediaSessionService, постоянные checkpoints).
`PlayerApplication` связывает их с `ShellModel`. Service создаёт и освобождает
единственный ExoPlayer; UI получает Android-независимый PlaybackController.
Сканирование работает на Dispatchers.IO, аудиокоманды — на главном потоке.
Решение и пределы: [ADR-003](DECISIONS/ADR-003-local-playback.md).

В M3.1 внутри `:designsystem` выделен пакет `skin`: контракт `AppSkin`, данные
`PrismSkin`, каталог векторов `PrismIcons`. `:feature:shell` использует только
семантические роли `UiIcon`; каталог, профили и аудио не зависят от оформления.
Новые repeat/shuffle и операции очереди проходят через `PlaybackController`;
реальный адаптер делегирует перестановки/удаления самому Media3 без пересоздания
плеера. Сохраняются порядок, текущий трек, позиция и режимы каждого профиля.

M3.2: `UserCollections` и чистые изменения списков находятся в `:core`,
`LocalCollections` с AtomicFile — в `:library:local`, `CollectionsScreen` — в shell.
Хранилище не получает `PlaybackController` или `ContentResolver`; оно меняет только
собственный файл метаданных. Явный запуск списка проходит отдельной командой `playQueue`.

M3.3: `ArtworkCache` и `StorageMonitor` находятся в `:library:local`. Ядро передаёт
необязательный URI обложки, shell читает уменьшенное изображение вне UI-потока
и использует форму/запасную иллюстрацию скина. AndroidPlayback хранит логическую
очередь со ссылками на недоступные файлы отдельно от доступных MediaItem движка.
Фиксированная иерархия «Назад» и двойной выход относятся только к shell/Activity.
Решения: [ADR-007](DECISIONS/ADR-007-navigation-artwork-storage.md).

M4: `:provider:yandex` реализует device OAuth, проверку аккаунта и шифрованное
Android-хранилище. `AccountAuth` в ядре отделяет состояние экрана от credentials,
владеет отменой/поколениями операций и порядком записи сессий. Shell получает
только контроллер и безопасный `AccountAuthState`; не вызывает HTTP и не читает токены.
`PlayerApplication` собирает зависимости, `ShellModel` сообщает текущий профиль.
Клиентская конфигурация относится к сборке, пользовательские токены — к данным
каждого профиля на устройстве. [ADR-008](DECISIONS/ADR-008-profile-yandex-auth.md).

M4.1: OAuth записывается до необязательных сведений Музыки; `AccountSession.account`
может отсутствовать. Ядро управляет повтором опроса до истечения кода и обновлением
сведений из сохранённого токена. Транспорт классифицирует HTTP/сетевые сбои без
передачи сырых ответов в UI. Store читает схемы 1/2; [ADR-009](DECISIONS/ADR-009-auth-legacy-parity.md).

M5: `OnlineMusic` в core управляет страницами и поколением запросов. `YandexMusicApi`
и отдельный HTTPS-транспорт находятся в provider:yandex. Shell отображает модели,
передаёт команды и читает уменьшенные публичные обложки вне UI-потока. AndroidPlayback
объединяет выбранные онлайн-ссылки с локальной очередью; сервис использует Media3
ResolvingDataSource для запроса временного URL. Токены и временные ссылки не входят
в checkpoint/MediaItem. [ADR-010](DECISIONS/ADR-010-online-catalog-playback.md).

M6: `MusicTaste` и `MyWave` в core задают типизированные реакции и контракт
рекомендаций; отдельные `YandexTasteApi`/`YandexWaveApi` используют общий HTTP
адаптер провайдера. `ArtistRef`/`albumId` сохраняют идентичность объектов в
очереди. `AndroidPlayback` владеет жизненным циклом волны и рекомендационной
очередью; `TasteControls` только отображает состояние и передаёт команды.
В M6 офлайн-синхронизация ещё не была подключена; [границы и перенос](DECISIONS/ADR-011-wave-and-taste.md).

M6.2: `WaveRecovery` задаёт ограниченные повторы, `WaveAudioBuffer` внутри playback
готовит и проверяет полный следующий файл отдельно от текущего Media3 loader.
Провайдер отдаёт кандидатов целой порции, core выбирает один новый трек.
Временный буфер текущего/следующего аудио не является постоянной офлайн-коллекцией.
[Карта рабочих методов 1.x и правила продолжения](DECISIONS/ADR-012-wave-continuation.md).

M6.3: `OnlineMusic` управляет вложенными карточками и возвратом к каталогу,
`YandexMusicApi` получает popularTracks/direct-albums. Shell рассчитывает компоновку
по доступным границам, оставляя аудио и реакции в прежних модулях.
`app/EqualizerLauncher` открывает выбранный DSP или системную панель; playback
предоставляет только текущий audioSessionId. Иконка EQ входит в контракт скина.
[Решение](DECISIONS/ADR-013-fixed-player-and-artist-cards.md).

M6.4: `PlaybackState.supportsQueueOrdering` отделяет обычный список от волны;
`AndroidPlayback` разделяет остановку работы и завершение источника. Системный
player facade направляет режимы в контроллер, а не непосредственно в ExoPlayer.
[Правила Stop/Pause и отображения](DECISIONS/ADR-014-wave-source-and-queue-order.md).

M7.1 добавляет `core/OfflineMusic` — состояние, принадлежность профиль/аккаунт,
подтверждённые лайки, поколения работы и отмена. `YandexLikedMusicApi` получает
только track likes; новый `:library:offline` проверяет и атомарно сохраняет файлы.
`app/OfflineSyncService` обеспечивает фоновую ручную загрузку; shell использует
контракты, playback выбирает проверенный офлайн-файл до сети.
[Границы и источники алгоритмов](DECISIONS/ADR-015-liked-offline-sync.md).

M7.2: `AudioQualityPreferences` в ядре хранит независимые предпочтения потока
и кэша. `app/AudioQualitySettings` сохраняет ключи в preferences, shell отображает
выбор. Плеер и синхронизация передают снимок нужного качества в `OnlineMusicApi`;
`YandexMusicApi` выбирает вариант по проверенной формуле 1.x. Настройки не владеют
плеером или файлами. [Решение](DECISIONS/ADR-016-audio-quality.md).

M7.3: `core/CloudPlaylists` управляет облачными командами и состоянием диалога;
`CloudPlaylistApi` реализован в `YandexPlaylistApi`, `app` связывает аккаунты и
обновление OnlineMusic. Контроллер не получает плеер или офлайн-кэш; операции
не меняют проигрываемую очередь. [Решение](DECISIONS/ADR-017-cloud-playlists.md).

- Core собирается/тестируется без Android runtime.
- UI не импортирует YandexMusicClient, DAO или implementation другого feature.
- Протоколы и backend проверяются отдельно от экранов; сборка приложения
  подтверждает подключение адаптеров, а не работоспособность внешнего API.
- RepoWise использовать для проверки связей после выделения модулей; зависимости
  подтверждать Gradle-конфигурацией и исходниками.
