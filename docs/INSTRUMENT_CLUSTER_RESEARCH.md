# Информация о треке на приборной панели

Исследование 7 октября2026, исходники `aa6a5d4`, текущий stable **2.5.1-build98**.
По запросу владельца исследуем совместимость; новый APK и изменение поведения
не выполняются. Это не обещание поддержки всех автомобилей.

## Что сообщил пользователь

Прочитана публичная тема1127108 прямым HTTP без входа и без публикации сообщений.
Веб-инструмент страницу не прочитал; HTTP вернул страницу, текст декодирован
из Windows-1251. Полный HTML оставлен только в игнорируемой `.local-build`.

[e.panchenko, сообщение№18](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=145405384)
сообщает: на ГУ отображение работает, подрулевые кнопки переключают;
Я.Музыка и FMPLAY показывают название и картинку на приборной панели,
при включении YMPlayer2 это окно исчезает. Это свидетельство пользователя,
не воспроизведение на нашем стенде. В сообщениях№15/18/20 нет модели машины,
ГУ, версии прошивки, точной версии YMPlayer и способа подключения телефона.
Сообщения разных авторов о других ГУ нельзя считать характеристиками его машины.
Снимок руководства показывает мультимедийный блок приборки, но сам по себе
не устанавливает модель автомобиля или протокол связи.

## Что YMPlayer уже публикует

- `playback/android/.../AudioService.kt`: общий Music/Radio
  `MediaLibrarySession`, `addSession`, `onGetSession` возвращает сессию;
  нет собственного `onConnect` со списком разрешённых пакетов.
- `AndroidPlayback.kt`, `Track.mediaItem`: TITLE, ARTIST, ALBUM, ARTWORK_URI.
  Отдельные displayTitle/subtitle и metadata.durationMs здесь не заданы;
  библиотека также использует длительность самого Player.
- `AndroidRadio.metadata`: TITLE — станция, ARTIST — песня/исполнитель,
  ARTWORK_URI — логотип; тип RADIO_STATION. Остановка эфира очищает media item.
- Manifest playback/android экспортирует сервис с интерфейсами Media3 и
  `android.media.browse.MediaBrowserService`; поддержан MEDIA_BUTTON.
- AudioAttributes: USAGE_MEDIA / CONTENT_TYPE_MUSIC. Notification и управление
  сессией поручены Media3; собственного автомобильного передатчика здесь нет.
- В manifest приложения нет `com.google.android.gms.car.application` и
  `automotive_app_desc.xml`. Полноценная интеграция Android Auto не заявлена.
- В production Music/Radio нет старых broadcasts
  `com.android.music.metachanged` / `playstatechanged`.

Используется Media3 **1.11.0** (`gradle/libs.versions.toml`). По
[документации MediaSession](https://developer.android.com/reference/androidx/media3/session/MediaSession)
Media3 создаёт активную системную MediaSession, доступную platform/legacy
контроллерам. Это не изолированная сессия, которую понимает только наш интерфейс.
`MediaSessionLegacyStub.start` в исходниках1.11.0 вызывает setActive(true).

Имеются прежние фактические свидетельства: [системные Browser/медиакнопки
в аудите97](qa/audit25-2026-10-07/emulator-5560-native-full.txt),
[CWG beta87](RADIO_CWG_VERIFICATION.md) и [системный снимок эфира](qa/radio-cwg-2026-10-06/playing-before.txt).
Это проверки соответствующих версий на эмуляторах; не новые проверки98 на
машине e.panchenko и не доказательство передачи на приборку.

## Как данные могут попасть на приборку

Нормальный общий вход — MediaSession. Служба автомобиля может получить
активные сессии через [MediaSessionManager.getActiveSessions](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)),
подписаться на изменения MediaController и передать текст/изображение
своей автомобильной подсистеме. Для чтения всех сессий нужны системные права
MEDIA_CONTENT_CONTROL либо разрешённый NotificationListener. Это права
получателя; добавление такого permission в YMPlayer не делает его передатчиком
на приборку и не решает вопрос автоматически.

Для Bluetooth есть подтверждённый стандартный путь: AOSP
[MediaPlayerList Android10](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android10-dev/src/com/android/bluetooth/avrcp/MediaPlayerList.java)
слушает активные MediaSession и получает контроллеры; далее данные использует
AVRCP. Этот путь применим, если звук/управление действительно идут через
Bluetooth. Наличие Android-ГУ само по себе не доказывает его использование.

Если приложения работают на телефоне через Android Auto, важна отдельная
[декларация media support](https://developer.android.com/training/cars/media/auto).
[Официальное описание FMPLAY](https://play.google.com/store/apps/details?id=ru.fmplay)
заявляет Android Auto и Bluetooth. Это подтверждает возможности приложения,
но не устанавливает способ, применённый форумным пользователем.

От Android до автомобильной приборки требуется реализация производителя.
Даже [Instrument Cluster API AAOS](https://source.android.com/docs/automotive/displays/cluster_api)
требует OEM-сервиса, взаимодействующего с конкретным оборудованием, и описывает
прежде всего вывод навигации. Его нельзя автоматически считать API передачи
музыкальной карточки на любую обычную Android-магнитолу. Транспорт может быть
CAN, другой канал или общий дисплей; наличие отдельного MCU пока не установлено.

Поэтому гипотеза владельца правдоподобна в части общего Android-источника.
Но вывод «работает у двух приложений — значит исключительно стандартный путь
без фильтров/ограничений производителя» из этого не следует.

## Конкретные различия и гипотезы

| Вариант | Что уже установлено | Как отличить и какой путь исправления |
| --- | --- | --- |
| Android Auto | Декларации Auto в YMPlayer нет, FMPLAY заявляет поддержку | Уточнить, где запущены приложения. При Auto — отдельная интеграция manifest/browser и проверка на DHU; сам по себе metadata-tag не доказывает исправление |
| Прошивка читает ограниченный набор полей | В Media3 есть TITLE/ARTIST и три artwork URI; bitmap записан в ALBUM_ART/DISPLAY_ICON, но не ART | Сравнить внешним platform-контроллером все ключи у трёх приложений, затем receiver/службу ГУ. Если требуется ART, адаптировать именно подтверждённый контракт |
| Прошивка выбирает/фильтрует источники | Пакетных ограничений в нашем callback нет; правила OEM неизвестны | Проверить конфигурацию штатного media-source и список/выбор активных сессий. При whitelist одной правки метаданных недостаточно |
| Старые уведомления о смене трека | YMPlayer их не отправляет; общий MediaSession уже есть | Искать receiver metachanged/playstatechanged в прошивке. Добавлять отдельный compatibility publisher только при доказанном получателе |
| Неполное состояние/первое событие | Сессия активна, прежний CWG получает изменения; данных с проблемной ГУ нет | Снять cold Play, смену трека, Pause/Resume и фон: состояние, actions, speed, metadata до/после загрузки обложки. Исправлять воспроизведённое расхождение |

Сравнение с1.x прочитано в `D:/_codex/YaPlay/.../YmpPlaybackService.java`:
метод putArtwork записывает bitmap одновременно в ALBUM_ART и ART.
[LegacyConversions1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/LegacyConversions.java)
в convertToMediaMetadataCompat пишет bitmap в DISPLAY_ICON и ALBUM_ART.
Также сохраняет TITLE, ARTIST, ALBUM, длительность и три URI; отсутствие ART
не является отсутствием обложки по Android API. Загрузка bitmap асинхронна:
[MediaSessionLegacyStub1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java)
сначала публикует metadata, затем обновляет её при получении изображения.
Служба ГУ, читающая только один ключ или только первое событие, могла бы
отличаться от CWG. Это проверяемая гипотеза, не установленная причина.

## Следующая проверка и критерий решения

1. Узнать модель/прошивку ГУ и автомобиля, версии приложений и место их
   запуска: ГУ, телефон/Bluetooth или Android Auto. Не подставлять K4811
   владельца вместо неизвестного оборудования другого пользователя.
2. На одном устройстве сравнить Я.Музыку, FMPLAY и YMPlayer с одинаковыми
   условиями. Снимать platform MediaController: пакет/активность, TITLE,
   ARTIST, DISPLAY_TITLE/SUBTITLE, DURATION, наличие/размеры ART, ALBUM_ART,
   DISPLAY_ICON и URI, PlaybackState/actions/speed, callbacks после смены
   трека и появления обложки. Только своего приложения в диагностике
   недостаточно для определения OEM-фильтра или отличий других плееров.
3. Разобрать штатный получатель метаданных из подходящей прошивки/APK:
   MediaSessionManager/NotificationListener, имена broadcasts, фильтры
   пакетов, читаемые поля и выходной вызов автомобильного сервиса.
4. По результату сделать минимальное исправление существующего экспортера
   либо доказанный отдельный адаптер совместимости. Не создавать вторую
   конкурирующую MediaSession и не менять applicationId ради whitelist.
5. Подтвердить отдельно текст, картинку, смену трека/станции, Pause/Play,
   переход между источниками и отсутствие старой карточки после Stop.
   Эмулятор доказывает Android-публикацию, автомобиль — конечное отображение.

Пока неизвестны конкретный receiver, transport и причина исчезновения окна.
Не исследован APK именно той версии FMPLAY/Я.Музыки, которая стоит у автора.
В этом этапе проведены HTTP-чтение, проверка актуальных исходников, сравнение
точной версии Media3 и существующих свидетельств. Физические устройства,
CAN/MCU и установленные приложения пользователя не изменялись.
Код, version.properties и update feeds сохранены; stable98 остаётся последним.
HTTP raw GitHub/основного CDN и GitHub latest сверены7 октября:
[датированный срез и hashes источников](qa/instrument-cluster-2026-10-07/research.json).

[НаблюдениеD-003](BUG_REPORT.md) · [текущий порядок](ROADMAP.md).
