# Выпуск YMPlayer2 2.7.0-build137

13 ноября2026 владелец поручил обычный релиз GitHub, затем сообщение от своего
аккаунта4PDA и обновление шапки. Дополнение: актуальные требования Android6+;
прошлые пакеты разделить на Android9+ и Android10+ по фактическому minSDK.
Ссылки на сообщения с APK и авторское оформление сохраняются.

Продвигается тот же подписанный APK137 без пересборки:4 664 183bytes,
SHA256 `6629c31ef15fc500eb0f158bd140c0607975b060681f116003f6896463854807`.
Пакет dev.petrov.ymplayer2, minSDK23/target36, прежний сертификат/v1+v2.
Проверенные исходники a2afe1c4ec0387a280af8504645baed9396a697e;
[CI43401951128 success](https://github.com/reziarlleh/YMPlayer2/actions/runs/43401951128).
Полная исходная матрица совместимости136 — [Android6+](ANDROID6_2_7_0_VERIFICATION.md),
исправление137 и native73×2/3 process-сценария — [B-048](WAVE_SOURCE_RETURN_2_7_0_VERIFICATION.md).
[Полный состав2.7.0](RELEASE_2_7_0_NOTES.md), [оригинал APK](MANUAL_2_7_0_BUILD137.md).

## GitHub и неизменность APK

[Обычный релиз2.7.0-build137](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.7.0-build137),
ID444073690: draft=false, prerelease=false, Latest. После загрузки draft все4
assets скачаны обратно и сверены, затем опубликованы. Публичные4 assets и оба
APK с jsDelivr/Gcore полностью скачаны с200 и совпали по байтам/размеру/SHA.
[Шесть полных загрузок](qa/release270/public-downloads.json).

- Source v-tag:abd647e1a2b373ebdc092719f4e6eecd303fa6e1. От проверенного a2afe1c
  отличаются принятие/документы/assets, продуктовые исходники не изменены.
- Компактный immutable apk-tag:e5997a4e2f57d3716f0fb0826451b34d2b6ca5f1,
  дерево4 679 651bytes, ниже50MB. Исходный тег/история не переписывались.
- APK, build.json, SHA256.txt и signature.txt сохранены в принятом виде;
  служебное поле publication в build.json фиксирует момент подготовки assets,
  текущая публикация подтверждена GitHub/feed и этим отчётом. APK не пересобирался.

[Latest,4 assets,полный cumulative body,public/main и оба тега](qa/release270/publication.json),
[проверенный distribution tag](qa/release270/distribution-tag.json).

## Обновления и документы

version.properties:2.7.0/stable, Build137, releaseState=accepted. Оба feed
подготовлены штатным Prepare-UpdateManifest.ps1 с min23, тем же SHA и
компактным immutable URL; отправлены в main d6fb891 вместе с документацией.

После двух purge проверены все6 URL: оба raw и основной jsDelivr stable
полностью совпали с137, включая releaseNotes. Основной CDN manifest и оба
Gcore feed ещё132. D-001 остаётся наблюдением внешнего кэша: успешный purge
не означает обновления каждого узла. При доступном новом источнике updater
выбирает наибольший валидный Build; старый ответ не перекрывает137. Сами
оба pinned APK137 исправны. [Независимый датированный срез](qa/release270/feeds.json).

README/обе инструкции, статус/план/карты, багрепорт, журнал и указатель приведены
к опубликованному137 и Android6+. Старые отчёты136/133 сохранены как свидетельства
своих проверок. Новая регрессия плеера ради публикации не запускалась.
Check-Documentation.py/--github прошёл:286 документов/2937 локальных ссылок,
0 ошибок/битых целей, Latest137 и README blob совпали. После финального push
сверка повторяется. [Итог](qa/release270/documentation-github.json).

## 4PDA

После GitHub опубликовано ровно одно [сообщение №109 отreziarlleh](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=145991001)
с APK36539326. По свежей форме обновлена шапка2.7.0/13.11.2026/Android6+,
APK36539381. История разделена9+/10+ по реальным APK minSDK. Оригинальные
файлы и ссылки сохранены; дублирующая копия132 из шапки заменена137,
канонический132 в№106 проверен до/после. Оба137 APK совпали с оригиналом.
[Форум, rebase автоматической правки и сохранность](FORUM_RELEASE_2_7_0_2026_11_13.md),
[машинная проверка](qa/release270/forum.json).
