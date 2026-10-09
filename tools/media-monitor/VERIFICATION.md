# Отдельный сборщик сравнения плееров на ГУ

9 октября 2026. По предложению владельца подготовлена **Media Monitor
1.0.0beta-build1** для диагностики D-003 на проблемном автомобильном ГУ.
Уточнение владельца: это не утилита для смартфонов/ТВ, у них нет приборки.
Эмуляторы ниже — только лабораторные стенды стандартных API, не целевые устройства.

## Реализация и выпуск

- [Исходники и инструкция](README.md), собственный пакет
  `dev.petrov.mediamonitor`; независимый Gradle-проект в том же репозитории/main.
  Не включён в сборку/модули/автоматическое обновление YMPlayer.
- Native Activity + системная NotificationListenerService + MediaSessionManager.
  Наблюдение opt-in, доступны начальный снимок и callbacks активных/token-backed
  сессий, тексты/состояния/действия/размеры bitmap, медиауведомления и отметки
  видимости приборки. Только `ru.fmplay`, `dev.petrov.ymplayer2`, `ru.yandex.music`.
- Пакет проверяется **до** чтения extras/metadata. Нет INTERNET, audio focus,
  собственной MediaSession и transport-команд. Export не содержит сырых token,
  URI, картинок, аккаунтов или чужого содержимого. В отчёте есть названия музыки.
- 30 минут / 4 МиБ, Stop сохраняет отчёт, новая запись заменяет его после
  подтверждения. Переподключения listener отмечаются; прошивка всё равно может
  прервать процесс. Лимиты проверены чтением условий/резерва STOP, не отдельным
  30-минутным нагрузочным прогоном.
- `.txt` UTF-8/JSONL: SAF, share через readonly private provider, без файлового
  диалога на API29+ — MediaStore Downloads; при недоступном провайдере — external
  app Documents. На Android10 можно скопировать файл менеджером на USB.
- Собственный счётчик утилиты: build1, не YMPlayer build100.
  Declared min23 / target36 / compile37; это не подтверждение работы на Android6.

Подписанный [APK](../../releases/media-monitor/1.0.0beta-build1/MediaMonitor-1.0.0beta-build1.apk),
[SHA256](../../releases/media-monitor/1.0.0beta-build1/SHA256.txt),
[подпись](../../releases/media-monitor/1.0.0beta-build1/signature.txt),
[build.json](../../releases/media-monitor/1.0.0beta-build1/build.json).
APK SHA-256: `a7eab2c93de6949d41bcbda323b67ba5a849f0ca312586a56add6f0fa09da425`.
Сертификат: `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
apksigner verify: v1/v2 успешно; v3/v4 отсутствуют, дополнительных требований
к этим схемам здесь не вводилось. Manifest не запрашивает uses-permission.

[Отдельный prerelease](https://github.com/reziarlleh/YMPlayer2/releases/tag/media-monitor-v1.0.0beta-build1)
исключён из latest. Stable2.5.1-build98, manual2.5.2beta-build99,
`version.properties` и два feeds остаются прежними.

Публикация проверена9 октября, 03:56 UTC: GitHub helper prerelease с четырьмя
вложениями, downloaded APK и GitHub asset digest совпали с локальным SHA-256.
`Check-Documentation.py --github`: 219 документов, 1980 локальных ссылок,
0 ошибок; README blob совпадает. GitHub latest сохранился v2.5.1-build98,
manual beta — v2.5.2beta-build99. Исходники/документы опубликованы на main.

## Выполненные проверки

| Проверка | Результат и границы |
| --- | --- |
| assembleDebug / assembleRelease / lintRelease | Успешно; lint 0 ошибок, 25 предупреждений: commit SharedPreferences и существующие рекомендации обновления toolchain/target. InlinedApi MEDIA_URI ограждён SDK26. Отдельно регрессию плеера не запускали |
| Лабораторный API29, `YM2_M1_TV`, emulator-5564 | Listener читает реальные FMPLAY2.4.18/x86 и signed YMPlayer99, текст, bitmap/хеш, MediaStyle/group/subText, смену станции/Pause и callbacks; Activity утилиты в фоне |
| Фильтр третьего пакета / посторонние данные | Framework-фикстура с пакетом ru.yandex.music дала metadata/state/notification/Next/Pause; другой пакет с PRIVATE_UNRELATED_CANARY отсутствует в отчёте. Это НЕ проверка официальной Я.Музыки |
| Журнал debug-пробы | 226 событий / 279976 байт; 0 ERROR; только разрешённые packages; Stop последним, последующие изменения плееров размер не увеличили. Физическая отметка проверена искусственным нажатием, приборки на стенде нет |
| Обычная выдача доступа | Лабораторный API28: системный Notification access → ALLOW, служба подключилась. Android TV29 скрывает этот экран: показано сообщение о недоступности, не мнимый успех |
| Подготовка API29 стенда | `cmd notification allow_listener` только на эмуляторе. Это не процедура пользователя и не обход закрытых прав на физическом ГУ |
| Отмена доступа | На стенде статус «выключен / не подключена», начать запись нельзя |
| SAF экспорт | API28: создан настоящий .txt в Downloads, 1770 байт, сообщение об успешном сохранении после записи; промежуточный нулевой файл не выдавался за готовый |
| Share / provider | Chooser вызван с readonly grant/ClipData, реальная отправка отменена. Источник provider ограничен именем export-копии/canonical папкой и режимом r; приватный журнал не публикуется |
| Подписанный APK / API29 | Установлен отдельно, запущен и обновлён тем же ключом в лаборатории. Listener видит signed99 и framework fixture; новый export сохранён MediaStore в Downloads/MediaMonitor, 47664 байт, без SAF и storage permission |

Первый черновик не собрался: setClipData возвращает void и совпали имена
переменных catch/Bundle. Исправлено до проверок. В fixture отсутствовали
repositories, а PowerShell разбил незакавыченный Gradle `-P`; исправлены
настройки и команда. Signing properties продукта не содержат keyAlias:
применён фактический alias `ymplayer2`, подтверждён сертификатом.
Старая UI-фикстура сначала искала некорректный MainActivity FMPLAY/регистр кнопок;
исправлена на реально разрешаемый FmplayActivity и отображаемые uppercase labels.
После PACKAGE_REPLACED Activity стенда запускалась повторно; это не объявлено
новым дефектом продукта. Итоговая сборка прошла.

## Что ещё неизвестно

На физическом ГУ Media Monitor пока не запускали. Не известно, доступен ли там
экран notification access и подключит ли прошивка службу. Официальная Я.Музыка
не установлена на наших стендах: реальный третий плеер должен попасть в отчёт
пользователя. Отчёт показывает Android-сторону; MCU/CAN и внутренний читатель
приборки через него не становятся известными автоматически. Нельзя закрывать
D-003 по этой разработке или выдавать лабораторные данные за измерение ГУ.

Следующий шаг: пользователь записывает на проблемном ГУ один сеанс трёх плееров,
присылает `.txt`; сопоставить notification/session порядок, group/subText,
bitmap/token присутствие и отметки приборки, затем выбрать проверяемое изменение.
[Действующий план](../../docs/ROADMAP.md), [продолжение D-003](../../docs/INSTRUMENT_CLUSTER_FOLLOWUP.md).

Позднее9 октября владелец указал не включать эту одноразовую утилиту в общие
документы проекта. Упоминания убраны из README, общего журнала, карт функций/
модулей и указателя; отчёт перенесён сюда из docs. В действующем плане остаётся
только исследование D-003, без расширения самого плеера и развития сборщика.
