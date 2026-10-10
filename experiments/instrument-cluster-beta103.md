# Приборная панель — отдельная beta103

9 октября 2026. Эксперимент ведётся в ветке `codex/instrument-cluster-beta103`
от stable `2.5.4-build102`. Основная `main`, stable update feeds, README,
CHANGELOG, PROJECT_STATUS, ROADMAP и рабочий журнал не меняются.

## Задача и гипотеза

На TENET T8/Android 10 beta99 уже передавала стандартные DISPLAY_* и ART,
но приборная панель осталась пустой. Сравнение с FMPLAY на эмуляторе показало
два оставшихся отличия: Media3 группировал уведомление ключом
`media3_group_key`, а legacy MediaSession имела flags=7 вместо 3.
Внутреннее поведение OEM-моста TENET неизвестно: эти отличия являются
гипотезами, а не установленной причиной.

## Изменения

- Media3 продолжает создавать MediaStyle-уведомление с прежними кнопками,
  каналом, token и асинхронной загрузкой обложки. Экспериментальный provider
  снимает app group и заполняет `subText` названием альбома/станции.
- `sessionPlayer()` больше не объявляет `COMMAND_CHANGE_MEDIA_ITEMS`:
  его операции add фактически no-op. Это приводит legacy flags к 3.
- Используется одна прежняя MediaLibrarySession. Android Auto, старые
  broadcast-команды, отдельная MediaSession и разрешения не добавлены.

## Проверки Android

- API28: адресный native-сценарий нового уведомления 1/1.
- Android TV API29: тот же сценарий и фоновое воспроизведение 2/2;
  PlatformMetadataTest + SystemBrowserTest 6/6 до последнего уточнения
  захвата `subText` (это уточнение не меняет сессию или browser).
- В сценарии настоящего AudioService проверены отсутствие группы,
  `subText`, title/text, большой значок, три кнопки, token, flags=3
  и работа Pause/Play через PendingIntent уведомления.
- Исходный тест на OnlineTestActivity не подходил: этот harness создаёт
  отдельную MediaSession без AudioService и не публикует уведомление.
  Проверку перенесли в LocalPlaybackTest с реальным сервисом. Одно ожидание
  ошибочно читало устаревшее поле `Notification.largeIcon`; `getLargeIcon()`
  показал существующий значок на API28/29.
- Release lint: 0 ошибок, 66 предупреждений.

## Подписанный APK

`releases/2.5.5beta-build103/YMPlayer-2.5.5beta-build103.apk`:
6 273 937 байт, SHA-256
`1180826c1e4d3fc74ed7de0bd98876168e670bd44a24c9d48addec98b3d889d7`.
Package `dev.petrov.ymplayer2`, versionCode 103, minSdk 28, targetSdk 36,
non-debuggable. APK Signature Scheme v2=true; сертификат SHA-256
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`
совпал со stable102.

Signed102→103/API35 и signed99→103/Android TV API29 установлены без очистки,
у обоих сохранился firstInstallTime, приложение запустилось. Эти установки
не являются приёмкой приборной панели или полной проверкой пользовательских
данных и настроек. Debug native-сценарии выше проверены отдельно.

## Публикация и остаток

Опубликован [GitHub prerelease v2.5.5beta-build103](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.5.5beta-build103)
с APK, build.json, SHA256.txt и signature.txt. Tag указывает на commit
`3c2b4c8`, содержащий APK и код эксперимента. Скачанный с GitHub release APK
побайтно совпал с локальным по SHA-256. GitHub asset digest также совпал.

Срез 9 октября 2026, 18:31 UTC: GitHub Latest — прежний stable102; default
branch `main` остаётся `d15f6cd`. Raw GitHub и основной jsDelivr `@main`
вернули build102 в `update/manifest.json` и `update/stable.json`.
Эксперимент не вошёл в stable feeds и основные документы.

`tools/Check-Documentation.py --github` проверил 223 документа и 1969
локальных ссылок без битых ссылок, подтвердил prerelease и прежний stable102
как GitHub Latest. Его итоговый код — 1 из-за двух ожидаемых замечаний:
`PROJECT_STATUS.md` и `ROADMAP.md` не объявляют beta103. Эти документы
намеренно не изменены по прямому требованию владельца об изоляции опыта.

Следующий шаг — результат на реальном TENET T8. При отрицательном результате
сравнить один отчёт Media Monitor для YMPlayer103 и работающего FMPLAY на
том же ГУ: тексты, token, group, flags, порядок изменения уведомлений и
состояние приборки. Без этой приёмки причина OEM-моста остаётся гипотезой.

Это Android-сторона, а не физическая приёмка TENET. Если панель по-прежнему
не показывает данные, нужна запись с проблемного ГУ, например уже выпущенным
отдельным Media Monitor, и сравнение с работающим FMPLAY на том же устройстве.

## Ручная проверка на TENET

Установить подписанную beta поверх текущего YMPlayer 2.x без удаления данных.
Запустить музыкальный трек с известными названием, исполнителем и обложкой;
проверить приборку при запуске, Pause/Play и переходе к следующему треку.
Зафиксировать, появилась ли карточка, какие поля и обложка видны, а также
версию установленного приложения. Для диагностики можно использовать
[Media Monitor](https://github.com/reziarlleh/YMPlayer2/releases/tag/media-monitor-v1.0.0beta-build1)
по его отдельной инструкции. ADB, root и Android Auto не требуются.

Beta и stable имеют один package и сертификат; установка версии с меньшим
versionCode поверх beta103 штатно не выполняется. Возврат без удаления данных
возможен с более новым stable Build.
