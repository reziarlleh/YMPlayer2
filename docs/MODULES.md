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

- Core собирается/тестируется без Android runtime.
- UI не импортирует YandexMusicClient, DAO или implementation другого feature.
- Протоколы и backend проверяются отдельно от экранов; сборка приложения
  подтверждает подключение адаптеров, а не работоспособность внешнего API.
- RepoWise использовать для проверки связей после выделения модулей; зависимости
  подтверждать Gradle-конфигурацией и исходниками.
