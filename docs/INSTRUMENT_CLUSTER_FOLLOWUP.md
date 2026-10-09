# Приборная панель: beta99 не помогла

9 октября 2026. Владелец передал снимок переписки с e.panchenko.
Автор сообщает: ГУ Android10, установка через USB, Android Auto не используется;
выдача разрешений через компьютер не изменила результат. После ссылки на
2.5.2beta-build99 ответил, что отображение на приборке не появилось.
Владелец уточнил: музыку проверяли точно; остальные источники не установлены.

## Что это меняет

Гипотеза «достаточно заполнить DISPLAY_* и ART» не подтвердилась на этом ГУ.
Сами поля в beta99 корректны по предыдущей проверке Android, но это не
устранение исходного симптома. D-003 открыт с отрицательным физическим результатом.
Не выдавать результат CWG/эмулятора за работу приборной панели.
Нехватка разрешений не подтверждена; не просить повторять их выдачу.
Сведений о конкретном выданном наборе прав/активной версии из dumpsys ещё нет.

## Повторное чтение реализации и сохранённых свидетельств

- Signed99 Music публикует TITLE/ARTIST/ALBUM, DISPLAY_*, ART/ALBUM_ART/DISPLAY_ICON
  и PLAYING со speed1. Проверка музыки пользователя не сводится к отдельному
  radio-снимку, где был speed0; он не доказывает причину общей неисправности.
- Обе реализации имеют стандартную Android MediaSession и MediaStyle с
  android.mediaSession token. FMPLAY на API29 выбирает framework-реализацию
  MediaSessionCompat; старый RemoteControlClient в его APK находится в ветке
  до API21. Просто наличие этого класса не основание добавлять его в YMPlayer.
- В сохранённом сравнении API28 notifications оба имеют category transport,
  PUBLIC, foreground и одинаковые flags0x6a. У FMPLAY заполнен subText и нет
  notification groupKey; у YMPlayer98 subText пуст и задан media3_group_key.
  Это кандидаты для проверки совместимости потребителя, не установленная причина.
  Current99 notification на проблемном ГУ пока не снят; старый снимок98
  не подменяет новое измерение. Media3 и FMPLAY используют стандартные pending
  intents и token, AudioService имеет session activity и экспортируемый browser.
- Поиск в доступной декомпиляции не дал подтверждённого необходимого
  metachanged/playstatechanged broadcast. Часть JADX не восстановлена;
  отрицательный поиск не доказывает отсутствие любого альтернативного канала.

[Предыдущее сравнение](FMPLAY_COMPARISON.md),
[проверки99](PATCH_2_5_2_BETA_VERIFICATION.md).
Официальные контракты: [Media3 platform/legacy bridge](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java),
[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

## Следующий шаг без новой сборки

Снять Android session + notification **на одном проблемном ГУ**, сначала
при играющем YMPlayer, затем при работающем FMPLAY. Нужно увидеть выбранную
media-button session, активность/состояние, notification token/bitmap/text,
группировку и включённых notification listeners. Снимок не раскрывает автоматически
внутренний MCU-путь, но позволяет проверить Android-сторону в реальных условиях.

Подготовлен [Collect-MediaBridge.ps1](../tools/Collect-MediaBridge.ps1).
Его запускает владелец авторизованного ADB-подключения: скрипт не запускает
плееры, не выдаёт права и не меняет настройки. Сохраняет только блоки двух
плееров, версии, OS/API и сведения о получателях уведомлений.

1. Подключить ГУ к компьютеру с ADB, выполнить `adb devices`, выбрать serial.
2. Запустить музыку в YMPlayer и убедиться в исходном симптоме.
3. Выполнить в PowerShell из корня проекта:
   `./tools/Collect-MediaBridge.ps1 -Serial SERIAL -Player YMPlayer -Adb C:/path/to/adb.exe`
4. Остановить YMPlayer вручную, запустить FMPLAY и дождаться отображения на приборке.
5. Выполнить ту же команду с `-Player FMPLAY`. Передать обе полученные папки
   из `.local-build/media-bridge-device` для сравнения.

Если сборщик выдаёт отказ ADB/permission или не находит record, сохранить текст
ошибки: не обходить доступ повышением прав. Формат dumpsys зависит от прошивки.
Скрипт проверен синтаксически и контролируемой фикстурой: все команды
только читают данные, нужные поля сохраняются, чужие notification/session
блоки и история не попадают в файлы. На физическом ГУ не выполнялся.
После снимков — адресная проверка отличия/потребителя и только затем решение
о следующем прототипе. Ни новая beta, ни понижение minSdk этим сообщением
не утверждены; stable98 и beta99/APK/feeds остаются прежними.
