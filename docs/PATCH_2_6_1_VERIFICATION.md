# Исправления2.6.1 — проверки и границы

Работа 13 октября 2026. Опубликован стабильный **2.6.1-build107** на основе106.
[Завершённый план](PLAN_2_6_1.md).

## Изменения

- Волна сохраняет историю Моей волны по профилю/UID; при повторной выдаче
  не проигрывает первую десятку снова. Raw курсор включает последнюю запись
  ответа, даже если она уже была/не имеет пригодной metadata. Застрявшая session
  восстанавливается station-путём1.x; setting seeds сохраняются в modern recovery.
- Продолжение списка сохраняет событие естественного конца до завершения
  асинхронного обновления источника. Новое состояние Media3 не стирает это событие.
- Prev/Next имеют доступность в PlaybackState, UI и MediaSession; metadata window
  не считается полной очередью. В клипах Next доступен при наличии следующей карточки.
- Cached taste и ordered actions сохраняются по профилю/UID. API проверяет итог
  многошаговой реакции; невыясненный результат сохраняет intent. Только подтверждённый
  track LIKE ставит автоматическую загрузку в долговечную очередь. Wi-Fi-only/
  cache-enabled сохраняют своё действие. Unlike/block исключают файл даже при
  ещё не изменившейся серверной ревизии и при новом manual sync.
- Экспорт:128/192/256/320 кбит/с, сохранение выбора; MP3 другого битрейта
  перекодируется, исходник нужного битрейта может копироваться. Авто отсутствует.
- Диагностика: operation/category, HTTP status, DNS/TLS/timeout, returned/accepted,
  попытки recovery и Media3 errorCode. Музыка/клипы/радио/кэш/экспорт, bounded журнал.
  Нет сообщений исключений, URL, токенов, ответов API, названий и UID в новых событиях.

## Окончательные проверки исходников

- [Сводка сборки](qa/patch261/build-checks.json): **141 JVM, 0 ошибок**;
  release lint **0 errors, 67 warnings**. Подписанная release-сборка успешна;
  OAuth-конфигурация сверена до выдачи Build и по DEX итогового APK.
- [API28](qa/patch261/api28.json): **55/55**, 242,7 сек.
  Wave recovery 10→11, история/restore/modes, короткий/длинный список,
  все четыре фактических MP3 bitrate, taste store, offline/online adapter,
  клипы startup/HLS/DASH/remote progress.
- [TV29](qa/patch261/tv29.json): **28/28**, 107,2 сек.
  Wave/EOT, пульт/фокус/настройки, четыре MP3 bitrate, persistent taste,
  системная остановка и UI выбора качества без изменения playback.
- [API35, общий прогон](qa/patch261/api35-batch.json): **39 pass/1 fail из40**,
  231,7 сек. Экспорт/перенос/background service/MP3/UI/все восемь языков/
  taste/волна/диагностика/локализация прошли. В SystemBrowser после успешной
  остановки радио и отсутствия polling не успел выполниться повторный Play.
  [Тот же сценарий отдельно](qa/patch261/api35-radio-retry.json) **1/1**;
  [весь SystemBrowser повторно](qa/patch261/api35-system-retry.json) **6/6**,
  20,58 сек, без изменения кода. Первичный отказ не скрыт и не объявлен
  новым исправленным багом; причина зависимости от общего прогона не доказана.
- EOF/source refresh: [до фикса](qa/patch261/eot-before-raw.txt) — тайм-аут8сек,
  [после](qa/patch261/eot-after.txt) — EOT pass (в том раннем прогоне был отдельный
  focus fail, впоследствии исправлен и покрыт финальными API28/TV).
  Reproducer использует настоящий ExoPlayer на main. Доказана клиентская гонка,
  но не идентичность причины на телефоне владельца.
- Предыдущие API28 прогоны32/55 имели31/54 pass и по одному focus fail.
  Исправлены переходы touch→remote в source picker и настройках волны;
  окончательные55/55 и TV28/28 приведены выше.
- **735 сообщений /8 полных языков**, проверка генератора.
  RU/EN export screenshots осмотрены: все четыре качества, шаблон, справка
  и кнопка экспорта помещаются на320×640dp API35.
  [Русский](qa/patch261/api35/export-ru.png), [English](qa/patch261/api35/export-en.png).

## Артефакт и публикация

Версия **2.6.1-build107**, stable, dev.petrov.ymplayer2, min28, target36.
Сертификат SHA256:
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
APK SHA256:
`512bf84647a7490eed7db15ec73a4cb2b22d794c57d713221b8692ec2f3975f5`.
[build.json](../releases/2.6.1-build107/build.json),
[подпись](../releases/2.6.1-build107/signature.txt).

[Signed106→107/API28](qa/patch261/signed-api28/signed-upgrade.json) без очистки:
версия107, масштаб100% до/после, восстановление страницы кэша после force-stop,
About107 и English UI открылись. Перед/после отмечен один checked control в теме
и кэше, но helper не сохранил его идентичность: это не доказательство сохранения
конкретного выбора. Native persistence/account проверки приведены отдельно выше.
Production107 получил настоящий device code; полный browser grant не выполнялся.
Helper ошибочно ожидал кнопку Cancel; получение кода уже прошло. Уход выполнен
Back, коды/токены/XML авторизации не сохранялись.

[GitHub release](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.6.1-build107)
Latest/non-beta, четыре assets APK/build.json/SHA256.txt/signature.txt.
[Серверная запись release](qa/patch261/release.json).
Source/artifact tag e440ae1a010f9e7bdf6ad65beddcd375c9dcba4d; оба feeds
опубликованы c106aab. 6924113 байт, GitHub/основной version-pinned резервный APK
совпали с локальным SHA256. [Индивидуальные HTTP-проверки](qa/patch261/http-verification.json).
Raw GitHub manifest/stable и основной jsDelivr manifest/stable107.
Gcore manifest/stable ещё106; это задержка внешнего CDN (D-001), не новая ветка.
Актуальные raw GitHub и основной jsDelivr доступны; только устаревший Gcore
не гарантирует обнаружение107. Очистка CDN запрошена, каждый узел проверен отдельно.

README остаётся пользовательским описанием; хронология — CHANGELOG,
инструкции RU/EN описывают automatic likes/pending actions/MP3 quality;
BUG_REPORT B-031…B-034 и карты функций/переноса обновлены.
[Локальная сверка ссылок](qa/patch261/documentation-local.json): 231 документ,
2079 ссылок, 0 ошибок. [GitHub-сверка](qa/patch261/documentation-final.json): 231 документ/2090 ссылок,
0 ошибок; Latest107 и README blob совпадают. [RepoWise](qa/patch261/repowise.txt) index-only завершился: 94 files,
12176 nodes/38223 edges,405 структурных страниц. Кандидаты dead-code не удалялись
без подтверждения. Финальная сверка после публикации записана в WORK_JOURNAL.

## Ошибки подготовки и найденные дефекты

1. Первый compiler читал прежнюю core-сигнатуру во время изменения зависимых
   исходников. Далее сборки запускаются только после завершения edits.
2. Обновлённый сценарий требует durable intent вместо прежнего запрета дальнейших
   действий после failed verification; исходный assertion больше не соответствует ТЗ.
3. Bulk provider добавляет12 файлов к двум существующим: ошибочное ожидание12
   исправлено на14. До этого тест не проверял EOT.
4. Новый EOT-тест сначала читал ExoPlayer с instrumentation-потока; затем использовал
   MediaSession wrapper, который только ставит выбор в очередь. Оба setup неверны:
   финальный reproducer читает/обновляет настоящий engine на main и доказал red→green.
5. Реальный дефект: offline membership следил лишь за verifiedRevision; pending unlike
   его не меняет. Добавлено сравнение desired keys и исключение pending targets при sync.
6. Helper перевода предполагал locations во всех старых catalog entries; исправлен
   optional доступ. Недостающий import/несуществующий RadioIssue.SIGN_IN исправлены.

## Границы результата

Настоящий персональный account grant, серверную выдачу при десятом треке,
физические USB/ГУ/TV не подменять fixture-проверкой. Все native результаты выше
получены на проектных эмуляторах. Сервер может не выдать новых кандидатов;
теперь NO_PROGRESS отличается от недоступного трека/HTTP/network/decoder.
Причина реальных клипов B-030 ещё не установлена; расширенный журнал позволит
отличить HTTP/network/Media3. Проект1.x и эксперимент приборки не изменялись.
