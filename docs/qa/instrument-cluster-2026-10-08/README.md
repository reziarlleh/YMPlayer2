# Сравнение FMPLAY и YMPlayer98

Исходные APK предоставлены владельцем; хеши, сертификат FMPLAY, совпадение DEX
и границы проверки записаны в `comparison.json`. APK и восстановленный чужой
код не включены. JSONL — выборочные внешние framework-снимки двух приложений
на одном API28. В TXT только выбранные строки соответствующих уведомлений.

Метод сборки DEX-инструмента такой же, как в [первой проверке](../instrument-cluster-2026-10-07/README.md).
Добавлены фильтр ru.fmplay, DISPLAY_DESCRIPTION и elapsed timestamp.
На API28 shell не имеет нужного права; использован root UID0 **эмулятора**:

```powershell
adb -s emulator-5560 shell 'CLASSPATH=/data/local/tmp/ymp-metadata-probe.dex su 0 app_process /system/bin PlatformMetadataProbe 1000'
```

Снимки получены опросом100 мс, а не перехватом всех callbacks. Успешные
Play/Pause/Stop/NEXT проверены через `input keyevent KEYCODE_MEDIA_*`;
`cmd media_session dispatch` на этой API не реализован и не использовался
как доказательство команд. FMPLAY слушал публичный эфир, YMPlayer — синтетические
SAF-файлы без Яндекс-токена. Root здесь — право исследовательского потребителя,
не требование к приложению для публикации metadata.

Воспроизведение после проверки остановлено. Обычный непривилегированный
клиент, Я.Музыка и отображение на приборке не проверены.
[Результат и следующий шаг](../../FMPLAY_COMPARISON.md).
