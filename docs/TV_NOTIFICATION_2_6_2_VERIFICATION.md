# Android TV — проверка 2.6.2-build113

14 октября2026. Source98c70a5, production APK113/min28, прежние package/подпись.
Физический KIVI 4K Android TV14/API34, stock tvlauncher/tvrecommendations.
Разрешения не выдавались, перезагрузки и очистки данных не было. Публикация завершается.

## Реальная системная карточка

| Сценарий | Результат |
| --- | --- |
| Музыка/Моя волна: Home → Open | MainActivity открывается; PLAYING, позиция продолжает расти |
| Музыка: Stop | PAUSED;212523мс через1/7с; карточка отсутствует |
| Повтор Play → Home → Open | PLAYING, возврат в плеер |
| Радио: медиатека → Home → Open | Возврат в радио, эфир не перезапускается |
| Радио: Stop | NONE;0мс через1/9с; карточка не возвращается |
| Пульт DPAD/Center: Open | Возврат в радио, PLAYING |
| Пульт DPAD/Center: Stop | NONE;0мс через1/8с; карточка отсутствует |
| Signed install-r | firstInstallTime2026-10-01 17:53:19 сохранён |

[Обезличенные измерения](qa/tv-card262-kivi/device-summary.json).
Shell am start использовался только для подготовки; результат Open измерялся
после настоящей кнопки Home. Radio position=-1 — неизвестная позиция live-потока.
Regex helper сначала не распознал её; исправлен измеритель, не приложение.
Первый DPAD Stop-прогон стоял на вкладке Home; фокус перечитан, настоящая кнопка
прошла отдельный прогон. Исходные отказы не объявляются успешными тестами.

## Документация и Android-контракт

Сверены [Android TV Now Playing](https://developer.android.com/training/tv/playback/now-playing),
[Media3 MediaSession](https://developer.android.com/media/media3/session/control-playback),
[фоновое воспроизведение](https://developer.android.com/media/media3/session/background-playback)
и [Activity/PendingIntent](https://developer.android.com/guide/components/activities/secure-bal).
Системная карточка управляется через сессию, её metadata/state/commands/sessionActivity.
Одна настройка TV-полей уведомления не исправляет системный контроллер.

| Граница | Реализация |
| --- | --- |
| Служба | MediaLibraryService, media service/browse filters, foreground mediaPlayback permissions/type |
| Сессия | ExoPlayer/MediaLibrarySession, platform bridge Media3; ForwardingPlayer transport |
| Metadata/state | MediaItem metadata/Media3; текущий источник виден в настоящей карточке |
| Аудиофокус | USAGE_MEDIA/MUSIC, handleAudioFocus=true, becoming noisy/wake mode |
| Доверие | Generic query NotificationListenerService, стандартный AcceptedResultBuilder; без разрешения всем клиентам |
| Открытие | Immutable explicit activity PI, UPDATE_CURRENT; player/radio, без Play |
| Android14 | Sender ALLOWED, creator option, permission-aware BAL для системного отправителя |
| Новые Android | Public creator setter>=35, ALLOW_ALWAYS>=36; ниже34 без новых API |
| Уведомление | Стандартный provider; TV availability/actions и поздняя обложка сохранены |

B-028: MediaSessionManager не видел установленный пакет NotificationListenerService.
Media3 1.11 выдавал untrusted/read-only, поэтому доставленная Pause не исполнялась.
Generic query делает пакет видимым; Media3 проверяет существующее MEDIA_CONTENT_CONTROL.
На Kivi trusted=true/playPause=true; команды сторонним недоверенным клиентам не расширены.

B-035: Android14 блокировал запуск от фоновой системной службы BOUND_TOP.
У неё уже есть START_ACTIVITIES_FROM_BACKGROUND, но нужен permission-aware opt-in.
Bundle key сверён с [AOSP ComponentOptions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/ComponentOptions.java)
и [BAL controller](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java).
Android проверяет существующее разрешение отправителя; новых grants нет.
SDK34 compatibility bundle не вызывает скрытых методов/reflection.
SessionActivity повторно привязан после notification; [разбор Media3](https://github.com/androidx/media/issues/2989)
сверен с реальным Kivi, а не принят как доказательство результата.

## Неудачные попытки

109 не исправил обе кнопки: заявление отозвано в GitHub и активных документах.
[Отозванный отчёт](qa/tv-card262-kivi/FAILED_BUILD109.md) сохранён как история.
110 исправил Pause/Open нет;111 отменял PI, копия которого оставалась в карточке;
112 сохранил токен и sender opt-in, но не учитывал permission системной службы.
110–112 не опубликованы. API29/own-UID тесты не покрывали реальный API34 путь.

## Сборка и границы

JVM core: 141 тестов, 0 failures/0 errors.
Неизменённый core допускает Gradle up-to-date; это не новый runtime-прогон Android.
[Сводка](qa/tv-card262-kivi/core-summary.json). Release lint0 errors/82 warnings;
debug lint0 errors/83 warnings. В stdout есть ошибка генератора lint quickfix
для переменной album в испанской строке; анализ/Gradle завершены успешно, текст
перевода не менялся. Это предупреждение инструмента, не Android runtime exception. Production release/OAuth guard прошли. Клипы, приборная
beta/утилита и1.x не менялись. Новых UI-строк нет; RU/EN инструкции сверены.

SHA256 APK: `3025634ccfa7def0ee2712a4ae90681fb0114288e89309d695863c6496851fee`.
Cert: `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Первый shell launch сразу после install-r113 дал BackgroundServiceStartNotAllowedException
в прежнем connect/startService из onCreate; повтор cold launch успешен.
Наблюдение D-005 не закрыто успешным повтором. Все последующие реальные Open/Stop113 прошли.

## Выпуск

Одна main, версия2.6.2/Build113. Tag/четыре assets, оба feeds/GitHub/CDN/hash
и документы сверяются до закрытия. Kivi проверен агентом; прочие TV/ГУ и
пользовательскую приёмку не подменять этим результатом. [План](PLAN_2_6_2.md).
