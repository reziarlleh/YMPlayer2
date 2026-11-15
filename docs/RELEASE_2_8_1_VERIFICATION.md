# Выпуск YMPlayer2 2.8.1-build145

15 ноября 2026 владелец разрешил публикацию. Выпущен обычный стабильный
[GitHub release/Latest](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.8.1-build145), оба source update feed —145/min23.
Публикуется исходный проверенный APK без пересборки: 4757699 bytes,
SHA256 `08d023356b06ca71f457040ef4f6d17d269acf7c740cd0cc80b5767cbcfbdb72`.
Пакет dev.petrov.ymplayer2, min23/target36, четыре ABI, прежний сертификат/v1+v2.
[Полный состав](RELEASE_2_8_1_NOTES.md), [план](PLAN_2_8_1.md),
[техническая матрица](OFFLINE_CACHE_2_8_1_VERIFICATION.md).

## Подтверждённая доставка

- Единственный Draft145 продвинут в regular/Latest; release ID445827772,
  четыре assets. Исходники продукта ed7fa354 не изменялись; последующие изменения
  тестовых fixtures не требовали пересборки. CI43680196168 success сохранён.
- Source tag `v2.8.1-build145` —81a1ee5aabf192f67b30faca8fde025d77e12dd4;
  compact tag `apk-2.8.1-build145` —63466a85f7ec090e394920162f757f68930fff3c.
  Оба созданы впервые, отправлены и перечитаны; дерево зеркала4773177 bytes.
- Четыре GitHub assets и два pinned APK jsDelivr/Gcore полностью скачаны,
  совпали с локальными байтами. [Загрузки](qa/release281/public-downloads.json),
  [GitHub/теги/состав](qa/release281/publication.json).
- Оба feeds подготовлены штатным инструментом, отправлены в main, затем
  выполнен purge. Независимая сверка полных JSON:5/6 URL актуальны. Только
  Gcore `@main/update/stable.json` ещё144; Gcore manifest уже145. Это датированное
  D-001, а не неисправность pinned APK. [Все шесть адресов](qa/release281/feeds.json).
- По разрешению владельца опубликовано одно [сообщение4PDA№115 с APK](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=146025001)
  и обновлена шапка2.8.1/15.11.2026/Android6+. [Форумный отчёт](FORUM_RELEASE_2_8_1_2026_11_15.md).

## Документы и границы

README, обе инструкции, статус/план, карты функций/модулей, багрепорт, журнал
и AGENTS приведены к stable145. D-008 закрыт в145; D-004/Fox_7 и другие
персональные наблюдения не объявлены исправленными. Новый набор целей не начат.
Проверка документов выполняется tools/Check-Documentation.py, после push —
с --github; результаты — `qa/release281/documentation-local.json` и
`qa/release281/documentation-github.json`.

Регрессия продукта ради публикации не повторялась: сохранены110 native23/35,
old-writer upgrade, actual reinstall,182+4 JVM, lint и signed144→145/R8 UI.
Разрешение выпуска не выдаётся за новую физическую проверку USB/ГУ.
Исходный [pending-паспорт](qa/release281/build-before-acceptance.json) сохранён;
паспорт у immutable тега отражает разрешение до публикации, её фактический
результат записан в publication.json. APK/signature/SHA не изменены.
RepoWise недоступен из-за tree_sitter, индекс не объявляется свежим.
