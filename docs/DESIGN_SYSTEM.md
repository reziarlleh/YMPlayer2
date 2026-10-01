# Минимальная дизайн-система

Обновлено: 2026-10-01. Базовое оформление «Оксид» реализовано в designsystem;
расширенные состояния и будущие компоненты ниже остаются проектной картой.
Токены определяются в одном UI-модуле. Domain не знает темы и размеров окна.

## Токены

2026-10-01: утверждена палитра №1 «Оксид» и монограмма №4 / r5.
Production-значения находятся в `PrismSkin.kt`; исторический id `prism`
сохранён для совместимости. Геометрия знака едина для Compose и launcher,
TV-плитка содержит название. [Векторные исходники](design/brand/README.md).
`NativeSkin.kt` переводит те же семантические токены в ClipPalette для native Views;
SideBarPalette хранит отдельные белые роли K4811. Значения не зашиты в feature/clips
или headunit/sidebar. Импорт внешних скинов относится к M13 / 2.1.0.

| Группа | Начальные значения |
| --- | --- |
| Spacing | 4, 8, 12, 16, 24, 32 dp |
| Typography | Caption 12, Body 16, Title 20/24, TrackTitle 28 sp; системный шрифт |
| Control | Обычный target 48 dp; основной transport 64 dp; icon 20/24 dp |
| Radius | Small 6, Surface 12, Sheet 20 dp; угловатый акцент отдельно от hit target |
| Motion | 120/180/240 ms; без циклического свечения, reduced motion учитывается |
| Focus | Контрастная рамка и фон; геометрия и размер элемента не меняются |
| Layout | Compact/Medium/Expanded/Large из RESPONSIVE_RULES |

Начальные семантические цвета:

| Token | Dark | Light |
| --- | --- | --- |
| background | `#191715` | `#EEE6DA` |
| surface | `#282420` | `#FFFAF2` |
| surfaceVariant | `#36302A` | `#E3D9C8` |
| textPrimary | `#EFE5D7` | `#29231E` |
| textSecondary | `#B6AA9A` | `#685C50` |
| primary | `#D77A50` | `#98431F` |
| secondary | `#B6B19A` | `#626044` |
| error | `#FFB4AB` | `#BA1A1A` |
| warning | `#F4C977` | `#815600` |
| success | `#8FDFC1` | `#146449` |

Медный primary и нейтральный оливковый secondary разделяют состояния. Обычный
текст и значки имеют самостоятельный контраст. Проверка реальных пар цветов
и всех состояний controls входит в Android-прототип; таблица не является
сертификатом доступности готового интерфейса.

## Компоненты и состояния

- AppShell: навигация, профиль, контент, общий статус и место постоянного плеера.
- Navigation: bottom bar/rail и TV focus policy при общих route IDs.
- PlayerSurface, MiniPlayer, TransportControls, Progress: одна модель состояния,
  разные композиции; не два владельца воспроизведения.
- TrackRow: artwork, название/исполнитель, источник, доступность, контекстное меню.
- AlbumCard/ArtistCard/PlaylistCard и DetailHeader: ограниченная ширина текста,
  отдельная семантика Play/просмотра; заглушка не считается настоящей обложкой.
- QueuePanel: текущий элемент, следующие элементы, возврат фокуса по MediaId.
- SourceFilter, SearchField, SortMenu: меняют выдачу каталога, не playback source.
- ProfilePanel: локальный профиль и его аккаунт, вход и явное переключение.
- StatusStrip/LogSheet; Loading, Empty, Error, Offline и Unavailable states.

У controls описать default/focused/pressed/disabled/loading. Disabled содержит
понятную причину; loading не создаёт повторные команды. Строки имеют стабильные
ключи. Макеты используют реалистичные длинные названия, отсутствующие artwork и
недоступный USB, а не только идеальный набор карточек.
