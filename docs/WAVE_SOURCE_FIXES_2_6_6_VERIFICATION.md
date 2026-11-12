# Волна2.6.6 — исправление расхождений аудита12 ноября2026

## Поручение и границы

Владелец поручил исправить все десять пунктов
[аудита исходников131](WAVE_SOURCE_AUDIT_2026_11_12.md). Затем отдельно уточнил:
запрет эмуляторов относился к анализу; для проверки исправлений они разрешены.
Работа продолжается в2.6.6. Безусловный Play, новая политика автозапуска,
функциональный этап2, радио, клипы и приборная панель не разрабатываются.

Все десять пунктов исправлены в **2.6.6-build132** и проверены целевыми
сценариями. Подписанный APK подготовлен локально; GitHub Release и перевод
автообновления не выполнялись. Техническая проверка не заменяет владельческую приёмку.
Исходный commit: `be1fb3b1e4ee79a4f45524026bc49eedac55f37f`.

## Изменения в исходниках

| Пункт | Исправление | Целевой сценарий |
| --- | --- | --- |
| WA-01 | `ShellApp` вызывает `retryPlayback`, который проверяет долговечный Play и ревизию; Retry больше не делает toggle | `delayedConnectionRetryCannotOverrideNewPause/Stop` |
| WA-02 | Touch toggle выбирает ровно показанное действие и вызывает тот же `playSession`/путь Pause, что MediaSession | `visiblePlayAfterFailedEndStartsOnFirstPress` |
| WA-03 | Удалён ранний выход touch Play по временной `available=false`; запрос сохраняется в playWhenReady до готовности метаданных | `touchPlayWhilePausedAccountLoadsIsExecutedWhenReady`, последующие Pause/noisy |
| WA-04 | NETWORK/SERVER не входят в ветку пропуска трека; повтор готовит тот же выбранный трек. Пропуск действительно недоступного/нечитаемого аудио сохранён | `transientNetworkErrorKeepsCurrentTrackAndPosition`, вариант SERVER503 |
| WA-05 | Истёкший сохранённый токен переводит вход в ERROR с запросом повторного входа, не в явный SIGNED_OUT. Окно сохраняется; сетевой доступ истёкшим токеном запрещён | `expiredSavedTokenKeepsPausedTrackPositionAndAllAudio`, вариант saved Play |
| WA-06 | Фоновые списки предпочтений отделены от событий новых локальных реакций. Фон не меняет paused окно; явный новый BLOCK может пропустить выбранный трек | `backgroundBlockedListsKeepPausedWindowButNewLocalDislikeCanSkip` |
| WA-07 | При restore не создаётся pending server batch из уже сохранённого successor; ремонт файла не меняет cursor конца окна | `fullRestoredWindowContinuesFromSavedTailCursor` |
| WA-08 | Media3 получает только непрерывный готовый будущий участок. Отсутствующий B не позволяет перейти A→C; B ремонтируется на своём месте | `missingMiddleFileCannotJumpToLaterCachedTrackOrLoseBack` |
| WA-09 | Системные false/noisy/focus сохраняют существующий stopped, включая false→false с новой причиной Media3 | `actualMedia3NoisyReasonAfterStopPreservesStopped` |
| WA-10 | Восстановление wave checkpoint и сверка его удалённых references обходят local/USB scan; волне не нужен запрос локальных tracks по её Yandex ID | `cachedWaveResumesWhileLocalLibraryIsStillScanning` |

Изменены [AndroidPlayback](../playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/AndroidPlayback.kt),
[WaveAudioBuffer](../playback/android/src/main/kotlin/dev/petrov/ymplayer2/playback/WaveAudioBuffer.kt),
[AccountAuth](../core/src/main/kotlin/dev/petrov/ymplayer2/core/AccountAuth.kt),
[MusicTaste](../core/src/main/kotlin/dev/petrov/ymplayer2/core/MusicTaste.kt),
[PlaybackController](../core/src/main/kotlin/dev/petrov/ymplayer2/core/PlaybackController.kt),
[ShellApp](../feature/shell/src/main/kotlin/dev/petrov/ymplayer2/shell/ShellApp.kt).
Новых строк интерфейса нет: используется существующее сообщение истечения входа
и существующие переводы; новые типы ошибок/сообщения пользователю не добавлены.

## Сохранённые ограничения и совместимость

- Stop по-прежнему очищает окно, аудио, session/cursor; Pause и завершение процесса
  сохраняют их. «Слушать волну» сразу начинает новую подборку с выбранными настройками.
- До десяти записей, включая предыдущую; текущий и следующий имеют приоритет.
  Для обычных плейлистов скачивание всего списка не включается.
- Повтор NETWORK/SERVER ограничен существующим бюджетом. Он не отменяет Pause/Stop,
  не меняет выбранный ID и не выдаёт сетевой отказ за недоступность трека.
- К checkpoint добавлено необязательное поле `waveAccount`. Старые данные читаются:
  владелец берётся из сохранённого индекса аудио либо восстановленной учётной записи.
  Повторный вход в тот же аккаунт сохраняет окно; другой аккаунт не наследует его.
  Доступ к файлам другого аккаунта через `WaveAudioBuffer.uri` не разрешён.
- Ручные Play/Pause и системные команды используют общие пути. Pause во время
  загрузки аккаунта готовится без звука после появления метаданных. Ошибка уже
  поставленного на паузу источника не создаёт цикл error→prepare.

## Проверка и найденные промежуточные ошибки

Используются настоящие Media3 и полные fixture-аудиофайлы на API28/API35;
Яндекс и реальные учётные записи не опрашиваются. Сверяются ID/позиция/очередь,
checkpoint, готовые файлы и долговечные Play/Pause/Stop, а не только отсутствие звука.

Первый целевой прогон:14/15 passed на каждом API. Оставшийся сценарий
`pauseAfterTouchPlayWhileAccountLoadsStillWins` сохранил ID/позицию/намерение,
но оставил движок IDLE вместо READY. Добавлена тихая отложенная подготовка
после явной или системной Pause; Stop её не запускает. Отдельная проверка
добавлена для noisy во время ожидания аккаунта. Финальный полный набор прошёл.

Первый вариант локальных событий предпочтений обновлял также LIKE до ответа
сервера; существующий JVM-тест запретил такое изменение. Оперативный overlay
оставлен только для сохранённого локального BLOCK. Подтверждённость сердечка
и запрет повторной отправки занятой реакции сохранены.

## Итог проверки

| Проверка | Результат | Свидетельство |
| --- | --- | --- |
| JVM core + buildSrc | 164+4, без ошибок | [Сводка](qa/wave-source-fixes266/jvm-lint.json) |
| Release lint | 0 errors, 82 прежних warnings | Та же сводка; [Gradle](qa/wave-source-fixes266/gradle-final.txt) |
| Native API28 | 75 passed, 0 failed; 1 optional long-idle skipped | [Сводка](qa/wave-source-fixes266/emulator-5560-regression.json), [лог](qa/wave-source-fixes266/emulator-5560-regression.txt) |
| Native API35 | 75 passed, 0 failed; 1 optional long-idle skipped | [Сводка](qa/wave-source-fixes266/emulator-5562-regression.json), [лог](qa/wave-source-fixes266/emulator-5562-regression.txt) |
| Идентичность проверенных исходников | Хэши совпадают после native-прогонов | [Kotlin и debug/test APK](qa/wave-source-fixes266/source-fingerprints.json) |
| Подписанный upgrade131→132 | Прежние UID/firstInstallTime, без clear, по2 cold/2 warm, установленный SHA совпал | [API28](qa/wave-source-fixes266/emulator-5560-signed132.json), [API35](qa/wave-source-fixes266/emulator-5562-signed132.json) |
| Первый целевой отказ | По14 passed/1 failed, IDLE после Play→Pause при LOADING | [Первичный результат до доводки](qa/wave-source-fixes266/initial-focused.json) |

Пятнадцать сценариев первого прогона не прибавляются повторно к75 финальным.
Финальный набор содержит также новый noisy при LOADING. Пропущенный optional
сценарий — отдельное реальное10-минутное ожидание; он в этом задании не запускался.
Длительный физический Bluetooth-простой и живая повторная авторизация Яндекса
не выдаются за проверенные на основании fixture-результатов.

[Подписанный132 и проверка обновления](MANUAL_2_6_6_BUILD132.md).
Планы, багрепорт, журналы, обе инструкции и описание всего цикла2.6.6
актуализированы. [Локальная проверка документов](qa/wave-source-fixes266/documentation-local.json)
не нашла ошибок/битых ссылок; README остаётся пользовательским описанием stable125.
Stable/Latest/feeds остаются125; [датированное чтение GitHub/raw](qa/wave-source-fixes266/public-state-before.json).
Форум не изменялся. RepoWise update отказал с `ModuleNotFoundError: tree_sitter`;
индекс не объявляется обновлённым, использованы непосредственные исходники.
