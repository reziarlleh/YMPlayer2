# Волна2.6.6 — системная медиакарточка и сброс очереди

## Установленный путь12 ноября2026

Диагностика владельца132 на Android16/API36 содержит:

| Время в присланном логе | Событие |
| --- | --- |
| 17:41:10.261 | PAUSE, AUDIO_BECOMING_NOISY, позиция117s, engineState3 |
| 17:45:49.416 | STOP, CONTROLLER, прежняя позиция117s, engineState3 |
| 17:47:41.485 | SERVICE_RELEASE уже со STOP/0s/engineState1 |
| 18:16:55.679 и21:10:53.074 | METADATA_ONLY восстанавливает уже сохранённый STOP/0s |

Между noisy-паузой и Stop прошло279.155s; сервис закрылся ещё через112.069s.
Очистка произошла до следующего открытия экрана. Владелец затем сообщил,
что смахивал системную медиакарточку на заблокированном экране. В прежнем логе
нет имени отправителя команды, поэтому конкретный процесс-сендер не объявляется
установленным только по CONTROLLER. Однако штатный путь смахивания воспроизведён.
Старый LAST_JAVA_CRASH относится к2.6.2-build116 от14 октября, не к этому сбою132.

## Первичные источники и причина

Используется Media3 **1.11.0**. Её
[DefaultMediaNotificationProvider](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/DefaultMediaNotificationProvider.java#L365)
ставит deleteIntent через createNotificationDismissalIntent.
[DefaultActionFactory](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/DefaultActionFactory.java#L106)
создаёт для него COMMAND_STOP и отметку NOTIFICATION_DISMISSED_EVENT_KEY.
По [контракту Player.stop](https://developer.android.com/reference/androidx/media3/common/Player#stop())
остановка освобождает ресурсы, сохраняя плейлист и позицию.

В132 наш sessionPlayer.stop направлял любой такой вызов в sessionStop →
stopSession с теми же последствиями, что у кнопки полного сброса волны:
stopped=true, удаление аудио/окна/session/cursor и0s. Это подтверждённая
ошибочная связь системной остановки с удалением сеанса, а не отказ сети,
потеря входа или установленная особенность марки телефона/магнитолы.

Новый тест через настоящий notification.deleteIntent.send на132 воспроизвёл
переход paused окна10 → пустое окно/stopped=true. Проверка сохранения паузы
упала. [Отрицательная проба](qa/wave-dismiss266/api35-red.json),
[лог](qa/wave-dismiss266/api35-red-dismiss.txt),
[след](qa/wave-dismiss266/api35-red-trace.json).

## Исправление и критерии

- Обычный MediaSession Stop останавливает движок/фоновые загрузки, сохраняет
  текущий трек, позицию, всё подготовленное окно и аудио. Сохранённый режим —
  тихое продолжение, без автоматического Play. Уже установленный явным Stop
  маркер остановки не снимается последующим системным Stop.
- Stop внутри приложения сохраняет прежний полный сброс волны. Кнопка Stop
  панели Android TV получает отдельный custom-action через штатный ActionFactory;
  NotificationProvider обрабатывает его как такой же явный сброс.
- Обычный deleteIntent и системный media token не заменяются при оформлении TV;
  отложенная загрузка обложки сохраняет оба разных действия.
- В диагностике различаются CONTROLLER_STOP и NOTIFICATION_STOP. Пользовательских
  строк/разрешений не добавлено; изменения поведения отражаются в обеих инструкциях.
- Целевые проверки: смахивание paused волны, обычный controller.stop и явный
  TV Stop; точные ID/позиция/10 файлов после восстановления. Проверяются API28,
  API35 и API36, подходящая регрессия волны/системных действий, JVM/lint и
  подписанный upgrade. Личный физический повтор не подменяется эмуляторами.

Работа продолжается в2.6.6. Подготовлен подписанный **2.6.6-build133**;
stable/Latest/feeds132 не изменяются до отдельной приёмки/публикации.
[APK и границы ручной проверки](MANUAL_2_6_6_BUILD133.md).

## Проверки исправления

Исправленная сборка debug/test APK,164 core+4 buildSrc JVM и release lint
прошли:0 ошибок,82 предупреждения прежнего набора. Первый compile test APK
выявил только доступ к internal companion из artwork-теста; тесту добавлена
та же INVISIBLE_REFERENCE-аннотация, которую использует остальная native-проверка.
На API35 и API36 прошли по три двухпроцессных сценария: dismiss,
controllerStop, tvStop. Каждый включает seed, завершение процесса, verify
обычного приложения без сети, настоящий noisy, действие карточки и возврат
в Activity. Проверяются точные ID/позиция/10 записей и сохранённый stopped.
Две фазы одного сценария не считаются двумя отдельными тестами.
[API35](qa/wave-dismiss266/api35-process.json), [API36](qa/wave-dismiss266/api36-process.json).

Для API36 установлен официальный Google APIs x86_64 image и создан собственный
AVD YM2_API36. Первый seed не прошёл ожидание пополнения окна; в тестовом wait
найдены повторное off-main чтение условия и wall clock. Ожидание переведено
на единое main-thread чтение и elapsedRealtime; последующие три сценария прошли.
[Первый отказ seed](qa/wave-dismiss266/api36-initial-seed-failed.txt) не объявляется
причиной физического отзыва или исправлением платформы.

На API28 выполнен полный набор пяти wave-классов:75 passed,2 failed,
1 optional long-idle skip. Оба отказа относятся к проверкам: новый тест
читал raw ExoPlayer с instrumentation-thread; старый «explicit Stop» вызывал
generic system Stop вместо явного Stop приложения. Чтение перенесено на main,
в тесте сброса указан именно player.stop; отдельно generic Stop сохраняет окно.
Оба исправленных теста затем прошли на том же API28. Итог по уникальным
сценариям77 passed/0 failed/1 skip — **сводка полного прогона и адресного
повтора**, а не заявление о втором полном прогоне.
[Первый прогон](qa/wave-dismiss266/api28-regression.json),
[адресный повтор](qa/wave-dismiss266/api28-corrected-stop-tests.json),
[сводка](qa/wave-dismiss266/api28-consolidated.json).

Медиапроверки API36:10 passed,1 Android-TV-Home-only skip,1 старое расхождение
теста выбора пустого кэша без входа. Последнее отдельно повторилось на текущем
APK и debug, собранном из исходников **до исправления Stop**, HEAD f3144ac.
До отрицательной проверки нет вызова нового sessionStop. Прежний локальный
список не играет, но origin остаётся LIST при отсутствующем account owner.
Это зарегистрировано как D-007, не скрыто сменой ожидания/skip и не выдано
за регрессию133. Остальные проверки Open/TV Stop, радио, поздней обложки,
медиатокена и браузера прошли.
[Прогон](qa/wave-dismiss266/api36-media-actions.json),
[отдельный повтор](qa/wave-dismiss266/api36-empty-cache-isolated.json),
[тот же отказ до фикса](qa/wave-dismiss266/api36-baseline-empty-cache.json).

[JVM/lint](qa/wave-dismiss266/jvm-lint.json):164 core+4 buildSrc passed,
0 ошибок lint/82 прежних предупреждения. Подписанный132→133 на API28 сохранил
UID/firstInstallTime без clear, по2 cold/2 warm запуска и SHA установленного
APK совпали. [Upgrade](qa/wave-dismiss266/api28-signed133.json).
Первый подписанный запуск API36 был перекрыт окном ANR com.android.systemui
собственного AVD после перезапуска с2GB RAM и не засчитан. После нормального
boot с3GB прошли2 cold/2 warm запуска уже установленного133, без
переустановки/понижения/clear; foreground, отсутствие delayed crash и SHA
проверены. Это startup-only повтор, не второй тест132→133 upgrade.
[API36 startup](qa/wave-dismiss266/api36-signed133.json).

[Хэши исходников и этапов test APK](qa/wave-dismiss266/sources.json),
[подписанный артефакт](qa/wave-dismiss266/release133.json).
Продуктовый код не менялся после выпуска подписанного133; последующие правки
касаются только двух тестов и документации. Физические устройства не использованы.
Полудневный личный сценарий и смахивание именно на lock screen Xiaomi остаются
владельческой проверкой; переданная Stop-команда и её обработчик проверены локально.

Исходники и отчёты отправлены в main:
[d4290de](https://github.com/reziarlleh/YMPlayer2/commit/d4290de81dee74c7573b286d0e41875d0a9c4364).
[GitHub CI43347531960](https://github.com/reziarlleh/YMPlayer2/actions/runs/43347531960)
завершился success: переводы, JVM/app unit, debug/test APK, lint и контракт ART.
[Сверка GitHub/документов](qa/wave-dismiss266/documentation-github.json) без ошибок;
[живые manifest/stable на13 ноября](qa/wave-dismiss266/live-feeds.json) сохраняют132
на GitHub/jsDelivr/gcore. Это публикация кода, не APK Release/новый auto-update.
