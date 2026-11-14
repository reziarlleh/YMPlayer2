# Выпуск YMPlayer2 2.8.0-build144

14 ноября 2026 владелец принял 144 и поручил GitHub → пост 4PDA → шапку.
[Обычный стабильный релиз](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.8.0-build144), ID444971492: draft=false,
prerelease=false, Latest. Оба манифеста автообновления опубликованы с 144/min23.
[Полный состав 2.8.0](RELEASE_2_8_0_NOTES.md), [форум №113/шапка](FORUM_RELEASE_2_8_0_2026_11_14.md).

## Тот же принятый APK

APK не пересобирался и не переподписывался:4700326 bytes, SHA256
`5247b2c178ead602c00496e65e6ee2d5838abbc67bacebbc1d8faa90ca197e60`.
Пакет dev.petrov.ymplayer2, min23/target36,4 ABI, прежний сертификат/v1+v2.
Проверенные исходники d6e6e0da0c0c1956070f1658f806c1ed52aa7930;
[CI 43593387619 success](https://github.com/reziarlleh/YMPlayer2/actions/runs/43593387619).
27 native23/35,182 core/4 app, debug lint 0/134/0NewApi и release lintVital —
[проверки 144](LOCAL_PROFILE_WAVE_RETURN_2_8_0.md). Новая регрессия плеера ради
публикации не запускалась. Прежняя профильная матрица 143/TV — историческая,
не объявляется повторённой на 144.

build.json дополнен ownerAcceptance=accepted, runtimeAcceptance=emulator-verified,
publication=approved-pending-publication и productSourceCommit. Publication —
срез подготовки assets; фактическое опубликование доказано GitHub/feed и этим
отчётом. Прежний pending-паспорт сохранён в QA кандидата, сам APK/signature/SHA неизменны.

Source v-tag96694ccbeadbfcbdb34362658adafd2d563d11f8 содержит 4 исходных assets;
компактный immutable apk-tag d70e225d5684b3cbd01671fe0ba479a780b267ea,
дерево 4715794 bytes, ниже 50MB. Теги созданы отдельно, история/старые теги не
переписывались; независимые ветки разработки не создавались.
[Источник/теги/полный body/assets](qa/release280/publication.json),
[компактный distribution tag](qa/release280/distribution-tag.json).

## Загрузки и автообновление

Четыре публичных GitHub assets и оба pinned APK на jsDelivr/Gcore полностью
скачаны с 200 и совпали с локальными файлами по байтам/размеру/SHA.
[Шесть загрузок](qa/release280/public-downloads.json).
version.properties:2.8.0/stable/Build 144/releaseState=accepted. Оба feed подготовлены
штатным Prepare-UpdateManifest.ps1 с тем же SHA/min23 и immutable apk-ref.

После двух purge проверены все 6 динамических URL: **4/6** полностью совпали.
Оба raw и оба основного jsDelivr —144; Gcore stable/manifest ещё 137. Это
наблюдение внешнего кэша D-001, не закрытое успешным purge. В первый срез
основной stable отдавал 403, после второго purge оба основного CDN подтверждены 144.
Updater выбирает наибольший валидный Build, старый ответ не перекрывает 144.
Сами оба pinned APK 144 исправны. [Независимый датированный срез](qa/release280/feeds.json).

## Форум и документы

Опубликовано одно [сообщение №113 от reziarlleh](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=146011128), APK 36547963.
Свежая шапка 2.8.0/14.11.2026/Android 6+, APK 36548046. Полный состав под спойлером,
137 — одной ссылкой в истории Android 6+, старые группы 9+/10+ сохранены.
Авторская структура, иконка вне спойлера и 10 прежних файлов сохранены;
оригинал 137 в №109 проверен до/после удаления только дублирующей копии из шапки.
Все 4 форумные загрузки подтвердили SHA. [Доказательства](qa/release280/forum.json).

README/обе инструкции, статус/план/карты, BUG_REPORT, AGENTS, журнал и указатель
обновлены до опубликованного 144. Локальная проверка Check-Documentation.py:
304 документа, 3160 ссылок, нет отсутствующих целей и ошибок согласованности.
[Результат](qa/release280/documentation-local.json). Проверка --github выполняется
после push; исправления, диагностика и будущие планы разделены.
Причина D-004/Fox_7 не объявляется исправленной. RepoWise context/update
недоступны из-за tree_sitter, индекс не объявляется свежим.
