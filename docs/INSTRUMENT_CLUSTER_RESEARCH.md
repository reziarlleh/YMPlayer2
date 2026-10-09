# Информация о треке на приборной панели

9 октября: пользователь сообщил об отсутствии результата beta99.
[Актуальное продолжение и сбор парных снимков](INSTRUMENT_CLUSTER_FOLLOWUP.md).

Исторический срез исследования до beta99. Дополнение экспортёра уже выполнено:
[2.5.2beta-build99 — результат и ограничения](PATCH_2_5_2_BETA_VERIFICATION.md).
Следующие указания «кандидат/не опубликовано» описывают момент исследования,
а не текущую точку продолжения. D-003 остаётся открытым до физического результата.

Сверено 8 октября 2026. Первый проход — 7 октября. Текущий stable **2.5.1-build98**; приложение,
APK и версия в исследовательском этапе не менялись. Следующее разрешение
владельца запускает [2.5.2beta](PATCH_2_5_2_BETA_VERIFICATION.md); stable98 сохраняется.

## Сообщение и границы задачи

[e.panchenko, сообщение №18](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=145405384):
на ГУ отображение и подрулевые кнопки работают; Я.Музыка и FMPLAY показывают
название и картинку на приборке, при включении YMPlayer2 карточка исчезает.
Прочитаны последние 20 сообщений темы публичным HTTP, без входа и публикации.
HTML декодирован из Windows-1251 и оставлен вне Git.

Первый план ошибочно поставил сведения об оборудовании впереди проверки
собственного Android-экспортера. Отсутствие декларации Android Auto было
вынесено в кандидаты без свидетельства, что автор использует этот способ запуска.
Владелец отклонил направление: проверяем стандартную публикацию Android и
разрешения, затем сравниваем приложения. Модель автомобиля/ГУ не является
предварительным условием работы. Симптом на приборке пока не воспроизведён
и не устранён; проверка Android ниже не является приёмкой конечного дисплея.

## Реализация

- AudioService: общий Music/Radio MediaLibrarySession, addSession/onGetSession;
  собственного onConnect с фильтром пакетов нет.
- AndroidPlayback: title, artist, albumTitle, artworkUri. Display-поля отдельно
  не заданы; длительность берётся также из Player.
- AndroidRadio: title — станция, artist — песня/исполнитель, artworkUri — логотип;
  тип RADIO_STATION. Stop радио очищает media item.
- Manifest экспортирует Media3 service и android.media.browse.MediaBrowserService,
  поддержан MEDIA_BUTTON. AudioAttributes: USAGE_MEDIA / CONTENT_TYPE_MUSIC.
- MediaSession и MediaStyle-уведомление обслуживает Media3 **1.11.0**.

[Документация MediaSession](https://developer.android.com/reference/androidx/media3/session/MediaSession)
описывает platform/legacy совместимость. В точных исходниках 1.11.0
MediaSessionLegacyStub.start вызывает setActive(true). Это не сессия,
доступная исключительно нашему интерфейсу.

## Проверка опубликованного build98 внешним контроллером

На **emulator-5562 / Android 15 / API35** прочитана framework-сессия подписанного
dev.petrov.ymplayer2 через MediaSessionManager / MediaController. SHA-256
установленного base.apk совпал с опубликованным локальным APK:
`edb5ec3797f9f32c5dd248df8d264da93f2f4bcec45902a08075d27e176e6706`.
Это обычный release, не .dev и не изменённый тестовый плеер.

Локальные синтетические файлы предоставлены существующим test SAF provider;
папка выбрана штатным системным picker. Аккаунт/токен Яндекса не использовались.
Внешний инструмент работает как **shell UID2000 на эмуляторе**, а не обычное
приложение без прав чтения чужих сессий.

| Наблюдение | Результат |
| --- | --- |
| POST_NOTIFICATIONS | granted=false на протяжении проверки |
| Play | flags=7; state=3/PLAYING; speed=1; actions=7340027 |
| TITLE / ARTIST / ALBUM | Cover fixture / YMPlayer tests / Original test artwork |
| Длительность | 29999 мс |
| Bitmap | ALBUM_ART и DISPLAY_ICON: 320×320; ART отсутствует |
| URI обложки | ART_URI, ALBUM_ART_URI, DISPLAY_ICON_URI присутствуют; значения не сохранялись |
| DISPLAY_TITLE / DISPLAY_SUBTITLE | null |
| Framework getDescription() | Cover fixture / YMPlayer tests; icon 320×320 |
| Уведомление | MediaStyle, category=transport, visibility=PUBLIC; title/text заполнены |
| Системные Pause / Play / Next | PAUSED / PLAYING и смена TITLE one → two видны внешнему контроллеру |
| Stop музыки | state=0/NONE, speed=0; последний TITLE остаётся |

Файлы one/two не имеют тегов и обложек: пустые artist/album/bitmap ожидаемы.
Cover fixture закончился до первого Pause и очередь перешла к one: это не
проверка Pause той же обложки. Снимки получены опросом; время первого события
и доставка всех callbacks отдельно не измерены.

[Датированный результат](qa/instrument-cluster-2026-10-07/platform-verification.json),
[исходник внешнего инструмента](qa/instrument-cluster-2026-10-07/PlatformMetadataProbe.java).

## Разрешения и различия

[Медиауведомления освобождены от POST_NOTIFICATIONS](https://developer.android.com/develop/ui/compose/notifications/notification-permission#exemptions).
Это соответствует проверке: при denied сессия, метаданные и уведомление есть.
Не найден пропущенный пользовательский permission, блокирующий этот путь.
[getActiveSessions](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))
требует MEDIA_CONTENT_CONTROL либо включённый NotificationListener у
**потребителя чужих сессий**, а не у нашего плеера для публикации своих данных.
Проверка API35 не доказывает одинаковое поведение всех внешних потребителей.

В 1.x (YmpPlaybackService.java, putArtwork) bitmap публиковался в ALBUM_ART
**и ART**. В [LegacyConversions 1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/LegacyConversions.java)
он идёт в ALBUM_ART и DISPLAY_ICON; runtime подтверждает различие.
Отсутствие ART не мешает самому Android получить картинку через getDescription().
Если иной потребитель читает только ART, это может иметь значение, но не
объясняет доказанно исчезновение всей карточки вместе с текстом.

[MediaSessionLegacyStub 1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java)
загружает bitmap асинхронно и обновляет metadata после загрузки. Сравнивать
нужно и первые данные, и изменения. Display-поля пусты, но framework корректно
использует TITLE/ARTIST. Старые broadcasts com.android.music.metachanged /
playstatechanged не отправляются; необходимость их добавления не установлена.
Общий MediaSession-путь есть, в том числе для
[AOSP Bluetooth/AVRCP](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android10-dev/src/com/android/bluetooth/avrcp/MediaPlayerList.java).

## Следующий шаг и возможное исправление

1. Сравнение FMPLAY/YMPlayer выполнено: перейти к оценке ограниченного прототипа
   заполнения DISPLAY_* и ART в существующем экспортёре, по [отчёту](FMPLAY_COMPARISON.md).
   Новая версия пока не выпускается. Я.Музыка не сравнивалась. Сведения о машине
   не ставить перед проверкой собственного Android-экспорта.
2. Проверить обычного внешнего клиента с разрешением потребителя отдельно
   от привилегированного shell-инструмента.
3. При подтверждённом различии исправлять существующий экспорт — display-поля,
   набор bitmap или обновления, если это действительно требуется. Не создавать
   вторую конкурирующую MediaSession и не менять пакет приложения.
4. Раздельно подтвердить текст, картинку, смену трека/станции, Pause/Play/Stop,
   переключение источников и конечное отображение на приборке.

Первый проход не получил FMPLAY из публичной загрузки. 8 октября владелец
предоставил ARM64 и x86 APK2.4.18; их classes.dex совпал. **Сравнение FMPLAY
и signed98 выполнено на одном API28**: FMPLAY заполняет DISPLAY_* и ART,
YMPlayer — нет; оба имеют действующую системную сессию и MediaStyle.
[Полный результат и конкретный путь прототипа](FMPLAY_COMPARISON.md).
Причина исчезновения приборки ещё не доказана. APK Я.Музыки и обычный
непривилегированный потребитель не проверены.

Физические устройства/настройки пользователя не изменялись. Воспроизведение
на эмуляторе после проверки остановлено; новый APK не выпущен. Предыдущий
[срез HTTP/исходников](qa/instrument-cluster-2026-10-07/research.json) сохраняется
как свидетельство первого прохода, не как доказательство приёмки.

[Наблюдение D-003](BUG_REPORT.md) · [текущий порядок](ROADMAP.md).
