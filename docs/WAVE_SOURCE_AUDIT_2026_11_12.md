# Волна: исходный код и утверждённая логика — 12 ноября 2026

> Исторический срез исходников131 до исправлений. Последующее поручение и текущее
> состояние WA-01…WA-10: [исправления и проверка](WAVE_SOURCE_FIXES_2_6_6_VERIFICATION.md).
> Ссылки на исходники закреплены за проверенным commit131.

Проверен код состояния **2.6.6-build131**, HEAD перед аудитом
`7d80961b7c28d514585f20130e91e1d69acc8a32`.
Продуктовый исходник соответствует тегу `v2.6.6-build131`
(`e8e20073eb8457b90f39ef8cf4280192e734026c`); последующий HEAD содержит документацию.
Основание сравнения — восемь правил работы волны, подтверждённых владельцем
12 ноября. Прежние отчёты и результаты тестов не используются как доказательство
соответствия. Проверены исходники приложения и вызываемые ими участки Media3
**1.11.0**, указанной в `gradle/libs.versions.toml`.

Эмуляторы, приложение, сборки и тесты не запускались. Внешние сервисы музыки
не опрашивались. Продуктовый код, APK, версия и update feeds не изменены.
Ниже — статические цепочки: описано, что выполнит код при указанных условиях.
Частота этих условий и причина конкретного отзыва владельца здесь не устанавливаются.
Все WA-пункты **открыты для последующего исправления**, исправлений в этом задании нет.

## Итог сопоставления

Полного соответствия утверждённой логике нет. Основной штатный путь реализован,
но есть десять условных путей расхождения: шесть первоочередных и четыре дополнительных.
P1 обозначает нарушение основных правил команд/выбранного трека, P2 — остальные
расхождения. Приоритет не означает, что условие наблюдалось у конкретного пользователя.

| Правило владельца | Что следует из кода | Расхождения |
| --- | --- | --- |
| 1. До10 записей с предыдущей; current/next раньше хвоста | `MAX_ITEMS=10`; хвост ждёт current job; полные файлы хранятся отдельно | WA-07, WA-08, WA-10 |
| 2. Pause сохраняет тот же трек, позицию и окно | Обычная Pause сохраняет файлы, отменяет автоматический error/EOF advance | WA-01, WA-06 |
| 3. Bluetooth disconnect означает Pause, reconnect сам не включает звук | Настроен noisy receiver; callback записывает Pause; собственного Bluetooth-autoplay нет | WA-01 — позднее восстановление сети; WA-09 — Stop превращается в Pause |
| 4. Закрытие/завершение сохраняет прежний Play/Pause/Stop | `onStop` сохраняет и завершает записи; detach оставляет полные файлы; foreground читает отдельное намерение | WA-03, WA-04, WA-05, WA-10 |
| 5. Явный Stop очищает окно и начинает следующий Play с новой подборки | Очередь/файлы/session/cursor очищаются, настройки сохраняются; старый stopped snapshot отбрасывается | WA-09 — последующий системный сигнал стирает маркер Stop |
| 6. Применение настроек немедленно запускает новую волну | Кнопка «Слушать волну» вызывает новый `startWave`, отменяет старую работу и очищает прежнее окно | В штатном доступном аккаунте соответствует; простое изменение выбора только сохраняет настройки |
| 7. Готовые файлы играют, пока подключается интернет | Восстановленные полные файлы доступны без API потока после готовности аккаунта | WA-04, WA-05, WA-08, WA-10 |
| 8. Последняя явная команда главнее запоздалой работы | Основная wave generation защищает Stop/новый источник; controller Play/Pause вызываются явно | WA-01, WA-02, WA-03, WA-06, WA-09 |

## Фактическая схема штатного пути

1. «Моя волна»/применение настроек → `command(playing=true)` → `startWave`.
   После проверки аккаунта старые jobs и файлы отменяются/удаляются, очередь
   очищается, выставляются новые параметры и начинается подбор первого трека.
   [AndroidPlayback:949](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L949),
   [WaveSettingsDialog:59](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/feature/shell/src/main/kotlin/dev/petrov/ymplayer2/shell/WaveSettingsDialog.kt#L59).
2. Первый трек может начать потоковое воспроизведение до готовности десяти файлов.
   Подготовка текущего запускается отдельно; следующий готовится в worker;
   дальнейший хвост ждёт current job. Окно ограничивается десятью логическими
   записями, с одной предыдущей.
   [fetchWave:1250](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1250),
   [WaveQueueWindow](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/core/src/main/kotlin/dev/petrov/ymplayer2/core/WaveQueueWindow.kt).
3. Pause сохраняет намерение `playing=false, stopped=false`, отменяет отложенный
   автоматический переход, ставит движок на паузу, записывает выбор/позицию.
   Готовые файлы и текущая логическая очередь остаются.
   [command:475](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L475),
   [sessionPause:1073](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1073),
   [freezeWaveSelection:1150](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1150).
4. Stop сохраняет отдельный stopped, очищает окно/файлы/session/cursor и
   ставит0s. При восстановлении stopped также подавляет старый snapshot очереди.
   [stopSession:705](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L705),
   [restore:1677](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1677).
5. Метаданные окна записываются отдельно от Play/Pause/Stop. Полные аудиофайлы
   публикуются после проверки и сохраняются с account-scoped индексом.
   `detach` сохраняет snapshot и отменяет работу с `discardAudio=false`.
   [checkpoint:1895](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1895),
   [detach:435](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L435),
   [WaveAudioBuffer](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/WaveAudioBuffer.kt).
6. Открытие приложения читает сохранённое намерение. Для удалённого источника
   отдельный foreground-запрос ждёт выхода аккаунта из LOADING. Saved Pause
   готовится без Play; saved Play вызывает `playSession`. Сама metadata-only
   регистрация сервиса этого запроса не создаёт.
   [PlayerApplication:62](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/app/src/main/kotlin/dev/petrov/ymplayer2/PlayerApplication.kt#L62),
   [resumeOnLaunch:145](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L145).

## WA-01 — поздний Retry подключения отменяет более новую Pause — P1

**Ожидание:** появление сети и старый запрос подключения не отменяют Pause.

**Цепочка:** `InternetCheck.retry()` оставляет `manual=true` до восстановления
сети. Пользователь после Retry нажимает Pause. Этот объект о новой команде не
узнаёт. Когда сеть появится, он вызовет `onReconnect(true)`. В `ShellApp` при
сохранившейся `waveIssue`/`error` и `!playback.playing` вызывается `player.toggle()`.
Пауза не очищает `waveIssue`, поэтому условие достижимо на paused волне.
`toggle` записывает новый Play и включает музыку.

Получается: Retry → Pause → сеть восстановилась → Play без нового нажатия Play.
Здесь нет проверки `launchRevision` или долговечного намерения, существующего
в момент выполнения отложенного callback.

**Код:** [InternetCheck:23](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/core/src/main/kotlin/dev/petrov/ymplayer2/core/InternetConnection.kt#L23),
[ShellApp:183](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/feature/shell/src/main/kotlin/dev/petrov/ymplayer2/shell/ShellApp.kt#L183),
[toggle:682](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L682).

**Для исправления:** повтор соединения/подготовки должен проверять последнюю
команду; старый callback не должен выполнять `toggle` и менять Pause на Play.

## WA-02 — видимая кнопка Play может первым нажатием выполнить Pause — P1

**Условия:** текущий трек закончился, готового следующего нет, подбор следующего
получил ошибку. `maintainWave` уже поставил `waveAdvance=true, waveResume=true`,
но `waveLoading=false`, а движок остался ENDED.

**Цепочка:** `publish` выставляет `state.playing=false`, потому что движок ENDED.
`PlayerScreen` показывает Play. Однако `toggle` рассчитывает команду как
`!waveResume`, то есть false. Первое нажатие видимого Play записывает Pause и
отменяет ожидающее продолжение; для нового запуска требуется ещё одно нажатие.
У controller Play другая ветка — он сразу вызывает `playSession`.

**Код:** [maintainWave:1194](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1194),
[publish:1866](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1866),
[кнопка:214](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/feature/shell/src/main/kotlin/dev/petrov/ymplayer2/shell/PlayerScreen.kt#L214),
[toggle:682](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L682).

**Для исправления:** согласовать показываемое действие и исполняемую команду
при ожидании/ошибке продолжения. Видимый Play должен означать Play.

## WA-03 — сенсорный Play во время загрузки аккаунта может потеряться — P1

**Условия:** восстанавливается paused волна. Метаданные уже прочитаны, но аккаунт
ещё LOADING. Поэтому сохранённый текущий трек имеет `available=false`, движок пуст.

**Цепочка:** кнопка Play разрешена, потому что `state.wave=true`. `toggle` через
`command` записывает Play, но затем выходит на проверке `current.available`.
После готовности аккаунта `syncQueue` делает `load(... resume=engine.playWhenReady)`;
это false — исходный обработчик движок не запускал. Старое ожидание
`PlayerApplication.restoreLaunch` больше не совпадает с captured saved Pause и
тоже не вызывает восстановление. Обычный maintenance не исполняет этот pending
Play. Музыка остаётся без звука до новой команды/следующего foreground repair.

**Код:** [PlayerScreen:190](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/feature/shell/src/main/kotlin/dev/petrov/ymplayer2/shell/PlayerScreen.kt#L190),
[toggle:695](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L695),
[доступность:1670](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1670),
[syncQueue:1806](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1806),
[foreground wait:87](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/app/src/main/kotlin/dev/petrov/ymplayer2/PlayerApplication.kt#L87).

**Для исправления:** принятая ручная команда Play должна дождаться готовности
своего трека/аккаунта и выполниться с проверкой более новых Pause/Stop.

## WA-04 — временный сетевой отказ может заменить восстанавливаемый трек — P1

**Условия:** сохранён Play трека A с1:25; полного файла A нет, но следующий B
подготовлен. Получение потока A временно заканчивается NETWORK/SERVER.

**Цепочка:** `onPlayerError` исключает только SIGN_IN/ACCESS и передаёт сетевой
отказ в `recoverWaveTrack`. Через300ms тот при наличии следующего MediaItem
выбирает B с0s, готовит и запускает его. Это не ожидание/повтор подготовки A
с1:25. Успешный старт B также закрывает launch recovery как состоявшийся старт.

Это условный путь при отсутствующем текущем файле или потоковом первом треке;
исправный полный файл A из отдельного кэша не требует такого сетевого запроса.

**Код:** [resolveStream:1583](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1583),
[onPlayerError:363](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L363),
[recoverWaveTrack:1232](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1232),
[launch completion:306](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L306).

**Для исправления:** отделить временную сетевую подготовку текущего трека от
пропуска действительно недоступной записи; сохранять выбранные ID и позицию.

## WA-05 — истечение входа удаляет сохранённую волну без Stop — P1

**Условия:** у сохранённых credentials задан `expiresAtMillis`, срок истёк
между запусками. Наличие такого срока поддерживается реальным provider-кодом.

**Цепочка:** `AccountAuth.restore` переводит аккаунт в SIGNED_OUT с сообщением
об истечении. `reconcileOnline` обрабатывает это так же, как явный выход:
пишет Pause, вызывает `cancelWave()` с удалением файлов, удаляет YANDEX-записи
из очереди и сохраняет результат с пустым текущим треком/0s.

Запрос повторного входа может ограничивать доступ к сервису, но по принятому ТЗ
не является командой очистки сохранённых трека/позиции/окна. Это не утверждение,
что токен конкретного пользователя истекал через несколько часов.

**Код:** [создание срока:121](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/provider/yandex/src/main/kotlin/dev/petrov/ymplayer2/yandex/YandexDeviceApi.kt#L121),
[restore auth:80](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/core/src/main/kotlin/dev/petrov/ymplayer2/core/AccountAuth.kt#L80),
[reconcileOnline:1724](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1724).

**Для исправления:** различать явный logout и необходимость восстановить доступ;
сохранить account-scoped snapshot, не обходя проверку входа для сетевых операций.

## WA-06 — фоновая синхронизация дизлайков меняет paused трек на0s — P1

**Условия:** волна на паузе A/1:25; после чтения сохранённых/серверных предпочтений
в blocked оказывается A или его артист. Это может прийти от фонового refresh.

**Цепочка:** подписка на все обновления taste вызывает `filterWave`.
Метод не различает явный дизлайк на этом экране и фоновую синхронизацию.
При blocked current удаляет A, выбирает successor B и делает `load(...,0)`
даже при paused состоянии. Звук останется выключенным, но A/1:25 уже заменён B/0s.

**Код:** [подписка:402](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L402),
[refresh предпочтений:132](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/core/src/main/kotlin/dev/petrov/ymplayer2/core/MusicTaste.kt#L132),
[filterWave:1374](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1374).

**Для исправления:** разделить явную реакцию пользователя и фоновые данные.
Фоновое обновление не должно незаметно менять выбранные ID/позицию паузы.

## WA-07 — восстановление полного кэша может откатить серверный cursor — P2

**Пример:** сохранено окно A…J, current A, server cursor J; все файлы готовы.
Restore одновременно восстанавливает cursor J и создаёт `pendingWaveBatch`
с next B и cursor B. Полное окно не требует worker, поэтому pending B остаётся.
При продвижении до C окно становится B…J и освобождает место. Готовые файлы
означают `queued=null`; worker берёт pending B и присваивает `waveBatch=candidate`.
Cursor становится B. Следующий API-запрос передаёт queue=B вместо J.

Это откат серверной точки продолжения, не аудиопозиции. Если сервер повторно
отдаст известные C…J, loader потратит дополнительные проходы/запросы на их
отбрасывание. Фактическая выдача сервера и вероятность повторов здесь не проверялись.

**Код:** [restore pending:1697](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1697),
[выбор pending:1267](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1267),
[замена batch:1296](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1296),
[реальный API:29](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/provider/yandex/src/main/kotlin/dev/petrov/ymplayer2/yandex/YandexWaveApi.kt#L29).

**Для исправления:** не использовать уже подготовленный successor как новый
server batch; сохранять cursor конца подготовленного окна при его пополнении.

## WA-08 — дырка в аудиокэше нарушает порядок и историю «назад» — P2

**Условия:** metadata-очередь A,B,C; A и C готовы, файл B отсутствует/укорочен.
Подготовка B не успевает завершиться до конца A, например из-за отсутствия сети.

**Цепочка:** `playableQueue` исключает будущий B, но включает C. Движок получает
список A,C. Автопереход и обычный Next могут выбрать C, минуя логический B.
После этого `WaveQueueWindow.retain` считает предыдущим B, удаляет A и его файл.
Поэтому сохранённый порядок пропущен, а Back уже не гарантирует возврат к A.
Repair missing-файлов есть, но он не блокирует обход дырки движком.

**Код:** [playableQueue:1417](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1417),
[Next:751](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L751),
[окно и удаление:1203](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1203),
[retain](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/core/src/main/kotlin/dev/petrov/ymplayer2/core/WaveQueueWindow.kt).

**Для исправления:** отделить наличие последующих готовых файлов от допустимого
следующего перехода; не терять фактически предыдущий трек при обходе отсутствующего.

## WA-09 — noisy после Stop стирает признак stopped — P2

**Условия:** Stop уже выполнен; сервис/движок ещё жив, noisy receiver включён.
Затем приходит отключение аудиовыхода. Media3 1.11.0 уведомляет и при false→false,
если изменилась причина playWhenReady, поэтому callback достижим на остановленном
движке. Receiver не проверяет, идёт ли сейчас воспроизведение.

**Цепочка:** callback вызывает `writeMusicIntent(false, reason=NOISY)`.
Аргумент `stopped` не передан и равен false. Сохранённый Stop заменяется Pause.
Очередь при этом остаётся пустой; самостоятельный запуск звука из одной этой
ветки не следует. Нарушен приоритет и сохранность именно режима Stop.

**Код:** [receiver включён:59](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AudioService.kt#L59),
[callback:338](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L338),
[writeMusicIntent:1880](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L1880),
[LaunchStateStore.write](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/LaunchStateStore.kt).
Дополнительно прочитаны `AudioBecomingNoisyManager.java` и `ExoPlayerImpl.java`
из исходников используемой Media3 1.11.0, а не документационные обещания:
[common sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.0/media3-common-1.11.0-sources.jar),
[exoplayer sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar).

**Для исправления:** системное false-событие на уже остановленном плеере не
должно сбрасывать явный stopped.

## WA-10 — готовая волна ждёт несвязанного сканирования локальных папок — P2

**Условия:** новый/пересозданный сервис, ready=false; локальная библиотека scanning.
Окно волны и текущий файл уже находятся в частном кэше.

**Цепочка:** attach не вызывает restore волны напрямую. Library collector
пропускает события при scanning и только затем устанавливает ready, вызывает
restore и исполняет pending команды. `command` также ждёт ready. При каждом
`MainActivity.onStart` запрашивается `library.refresh`; сам refresh выставляет
scanning=true. Таким образом, готовый текущий трек может ждать весь обход
локальных папок/USB, не имеющий отношения к его аудио.

Это условная зависимость старта, а не измеренный срок задержки. Время зависит
от фактического сканирования; замеров в этом задании нет.

**Код:** [attach:403](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L403),
[command:522](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt#L522),
[onStart:58](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/app/src/main/kotlin/dev/petrov/ymplayer2/MainActivity.kt#L58),
[scan gate:149](https://github.com/reziarlleh/YMPlayer2/blob/e8e20073eb8457b90f39ef8cf4280192e734026c/library/local/src/main/kotlin/dev/petrov/ymplayer2/library/SafLibrary.kt#L149).

**Для исправления:** готовность wave snapshot/аккаунта/частного аудиокэша отделить
от окончания локального сканирования, сохранив проверку выбранного профиля.

## Что не объявляется отдельным багом

- На середине трека штатные Pause/noisy → сохранение → restore имеют прямые
  защитные ветки. Найденные исключения перечислены выше; не утверждается,
  что каждое обычное открытие обязательно теряет позицию.
- Default Media3 timeout в прочитанном коде обновляет notification, сам по себе
  не вызывает Pause. `onTaskRemoved` вызывает Pause синхронно и обёрнут флагом
  служебного завершения. Из этих участков не выведен новый дефект.
- Позиция хранится целыми секундами; периодический checkpoint и `apply` не дают
  математической гарантии последней миллисекунды при обесточивании до записи.
  Это ограничение точности/сохранения, не доказательство потери середины на0s.
- Обычные повторы волны ограничены тремя, launch recovery — шестью/пятью минутами.
  После исчерпания попыток одна лишь сеть не обязательно перезапустит worker.
  Восемь правил не задавали бесконечный retry, поэтому автоматическое ожидание
  после исчерпания бюджета — отдельная точка согласования, не выдуманная новая цель.

## Приоритет для следующего поручения

Сначала WA-01/02/03: последняя команда и единая семантика Play во всех входах.
Затем WA-04/05/06: сохранность выбранного трека/позиции при временном отказе,
необходимости входа и фоновых предпочтениях. После — WA-07/08/09/10:
продолжение подготовленного окна, неполный кэш, маркер Stop и готовность старта.
Новый Build не выделен; публикация131 и стабильный канал125 в этом задании не менялись.
