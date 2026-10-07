# Проверка стандартной Android-публикации

`research.json` — первый проход исходников/HTTP. `platform-verification.json`
и `permission-denied-*.jsonl` — последующая проверка настоящего build98 на API35.
`notification-selected.txt` содержит только строки нашего MediaStyle-уведомления.
В снимках только синтетические треки; токенов и значений URI нет.

`PlatformMetadataProbe.java` — внешний framework-потребитель для эмулятора.
Он опрашивает активные сессии и выводит изменения выбранных полей JSON;
это не проверка доставки каждого callback. Привилегированный shell UID2000
имеет доступ к чужим сессиям, обычному приложению нужны права потребителя.
Production APK менять или выдавать ему MEDIA_CONTENT_CONTROL не требуется.

Повторение: скомпилировать Java с Android SDK android.jar (`javac --release 8`),
преобразовать class в DEX SDK-инструментом D8, отправить classes.dex на
эмулятор в `/data/local/tmp/ymp-metadata-probe.dex`. Во время воспроизведения:

```powershell
adb -s emulator-5562 shell 'CLASSPATH=/data/local/tmp/ymp-metadata-probe.dex app_process /system/bin PlatformMetadataProbe 1000'
```

Аргумент — длительность опроса в миллисекундах. Инструмент инициализирует Looper
и модульный media manager API35 для app_process; отсутствие этой инициализации
в первых попытках было ошибкой инструмента, а не плеера. Воспроизведение/SAF
подготовлены отдельно через обычные UI и системные команды.

Сравнение FMPLAY/Я.Музыки, неповышенный внешний потребитель и приборка не
проверены. Новая версия не выпущена. [Актуальный отчёт](../../INSTRUMENT_CLUSTER_RESEARCH.md).
