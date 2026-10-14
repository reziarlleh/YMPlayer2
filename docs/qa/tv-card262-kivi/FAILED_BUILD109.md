# Отозванный отчёт build109

История неудачной попытки, не доказательство исправления. На физическом Kivi
Open/Stop не работают. Актуальный результат — [проверка 2.6.2](../../TV_NOTIFICATION_2_6_2_VERIFICATION.md).

# Карточка Android TV — исправление2.6.2

Работа13 октября2026, основа107. [План](../../PLAN_2_6_2.md).
Финальный кандидат **2.6.2-build109**, публикация завершается на main.
Source:4361528. Кандидат108 не опубликован, его номер не переиспользован.

## Итоговые проверки

| Проверка | Результат |
| --- | --- |
| Android TV29 /5564 | 13/13 без отказов, включая реальные кнопки Android TV Home |
| Android9/API28 /5560 | 12/12;1 TV Home UI skip на телефонном AVD |
| Android15/API35 /5562 | 12/12;1 TV Home UI skip на телефонном AVD |
| Release lint | 0 errors /82 warnings; новых предупреждений в TV provider нет |
| Signed107→109/API28 | Установка поверх, route после force-stop и scale100% сохранены; firstInstallTime остался2026-10-07 |
| APK/package/min | dev.petrov.ymplayer2, code109, min28, stable,7001921 bytes |

Повторная Pause во время ожидания конца волны не проигрывает поздний ответ.
Радио Stop/Pause прекращает polling/reconnect; Open открывает player/radio
из Home и после остановки, не меняя Play/Pause. TV fields и available=true
проверены в первой и отложенной публикации обложки. Алгоритмы playback/provider
не менялись. UI строк не добавлено; инструкции RU/EN обновлены.

SHA256 APK: `79b98812563358fd6bc87064d3f8c0d53de86b388479d6f83a6c87086eafaa11`.
Прежний cert SHA256: `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Все первичные и итоговые логи: [qa/tv-card108](../tv-card108/).
Название папки соответствует первому кандидату; финальные логи относятся к109.

Это проектные эмуляторы и controlled API, не телевизор владельца. Причина
именно его оболочки пока не доказана; B-028/B-035 сохраняют открытую физическую
приёмку. Код клипов, изолированные beta/утилита приборки и1.x не менялись.

## Первичные отказы сохранены

- До исправления107 падает TV Open contract. Первый green11:8 pass/3 fail —
  UI selector Open и ActivityScenario cleanup. Новый harness проверяет lifecycle
  новой/существующей Activity; скрытые отказы не объявлялись runtime успехом.
- Затем10/11: требование исчезновения app-card1001 после системной Pause было
  неверным; уточнён реальный background-card4321/tray_dismiss путь.
- Первый framework13:12 pass/1 fail до Pause — тест отправлял disabled Next
  на ещё не готовую предзагрузку. Изменена подготовка: естественный конец трека.
- TV final-initial12/13: existing browser mediaItemCount маскировался асинхронно;
  отдельный повтор всего SystemBrowser7/7 на том же APK. Финальный TV13/13 прошёл.
- API35 initial11/12+1 skip: headless Radio start до Stop, затем foreground
  fixture/repeat12/12+1 skip. Не приписываем этот отказ обработчику Stop.
- Signed upgrade: первый UI dump был устаревшим в момент перехода и вернул
  пустой scale. Свежий dump подтвердил100% без изменения приложения/настроек;
  обе JSON-квитанции сохранены. Пустые labels radio/checkbox в первом helper
  не доказывают точное значение всех настроек, поэтому это ограниченное свидетельство.
- Кандидат108: поздно обнаружен default flags=0 при TvExtender(notification)
  без исходного extension. Финальный source использует TvExtender() и проверяет
  isAvailableOnTv. [Причина](../../LESSONS_LEARNED.md).
- RepoWise после source commit: index-only/no-agents,11 files,
  12250 nodes/38464 edges,273 структурные страницы,6м23с. Findings — гипотезы,
  runtime callback классы по ним не удалялись; [лог](../tv-card108/repowise-final.txt).

## Дефект и граница прежней проверки

Владелец сообщил: Stop только скрывает системную TV-карточку, музыка играет,
карточка возвращается. Open не реагирует. B-028 переоткрыт, B-035 добавлен.
Прежний SystemBrowserTest посылал generic notification.deleteIntent и
MediaBrowser.stop. Это проверяло Android/Media3 команды, но не TV-specific
PendingIntent, работу Open и конкретный лаунчер.

В исходниках107 уведомление создавалось стандартным Media3 provider без
TvExtender. Новый native tvCardHasExplicitOpenAndStopIntents на TV29/5564
перед изменением кода падает: «TV Open must have its own activity PendingIntent».
Лог .local-build/tv-card-red.txt; исходный APK tv-card107-before.apk сохранён локально.

## Системная карточка Android TV и отдельное уведомление приложения

Это Android TV, не Google TV. На проектном TV29 используется Android TV Home
`com.google.android.tvlauncher`. В установленном компоненте
`com.google.android.tvrecommendations` подтверждён отдельный путь фонового аудио:
карточка `Notification.NowPlaying`, id4321, её Stop отправляет
`com.android.tv.action.STOP_BACKGROUND_AUDIO`; NotificationInteractionReceiver
вызывает framework `MediaController.TransportControls.pause()`. Open берётся
из sessionActivity. Это не generic deleteIntent уведомления приложения id1001.

После системной Pause обычное уведомление приложения может оставаться: это
не означает, что фоновая системная карточка вернулась или звук играет.
Дополнительно проверяем неподвижность позиции, framework STATE_PAUSED,
сохранённый playing=false и отсутствие активной системной карточки4321.
Для волны — Pause через настоящий framework controller при блокированной
предзагрузке, затем поздний ответ; для радио — прекращение polling/reconnect.

APK системного компонента прочитан с собственного эмулятора, не с телевизора
пользователя; бинарник не публикуется. Полная JADX декомпиляция застряла,
поэтому нужные методы прочитаны адресно через dexdump. Это доказательство
пути проектной оболочки, а не реализации всех Android TV.

## Реализация

- TvMediaNotificationProvider декорирует стандартный Media3 notification:
  default TV availability, отдельный TV contentIntent и TV deleteIntent с COMMAND_STOP. Сохраняется
  notificationId, канал, MediaStyle/token/actions/обложка. Отложенная обложка
  проходит через тот же decorator, не стирая TV intents.
- Явный immutable Activity PendingIntent открывает MainActivity; NEW_TASK/
  CLEAR_TOP/SINGLE_TOP и creator BAL opt-in API35+ для пользовательской кнопки.
  Нет самостоятельного запуска Activity по таймеру/событиям воспроизведения.
- MainActivity обрабатывает Open при создании и onNewIntent; ShellApp получает
  внешнее навигационное событие. Music → player, Radio → radio. Open не посылает
  Play: остановленное остаётся остановленным, играющее не ставится на паузу.
- Радио native Stop переведён на TV-specific deleteIntent. Generic dismissal
  оставлен как отдельная проверка. Добавлены реальные Home → PendingIntent →
  выбранная страница плеера/радио, Stop → тишина/неподвижная позиция/нет карточки,
  повтор Play и прекращение on-air polling.

## Первичные источники и границы

[TvExtender](https://developer.android.com/reference/androidx/core/app/NotificationCompat.TvExtender)
документирует TV content/delete intents и generic fallback. Не утверждаем,
что отсутствие extension обязательно ломает любой TV-лаунчер: стандартный
fallback должен работать. Реализация конкретного лаунчера владельца не получена;
TvExtender — подтверждённый пропущенный путь совместимости, физическая причина
до приёмки не доказана.
[Media3 ActionFactory](https://github.com/androidx/media/blob/release/libraries/session/src/main/java/androidx/media3/session/MediaNotification.java)
предоставляет COMMAND_STOP; [правила BAL](https://developer.android.com/guide/components/activities/secure-bal)
описывают PendingIntent opt-in. Новых разрешений нет.

TV system APK7.4.0-848496343-f/code1010926453 прочитан только на собственном AVD;
SHA256 `8138be5909fba9f7eee14e8d1ce6d136bf7e3ecfea87a074ea6be24969132b0e`.
Сам APK не включён в репозиторий. Следующий шаг — публикация/tag/оба feeds,
отдельные HTTP/hash проверки и сверка документов; затем физический отзыв TV.
