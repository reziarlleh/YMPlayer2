# Собственные видеофикстуры

Генератор: `tools/make-clip-transport-fixtures.ps1 -Ffmpeg <path>`.
В этом прогоне использован FFmpeg 7.1 (локальный bundled binary).

Источник создан фильтрами `testsrc2` и `sine`, а не скачан из аккаунта:
160×90, 10 fps, 16 секунд, H.264 и AAC. HLS содержит восемь TS-сегментов
по две секунды; DASH — отдельные init/media-фрагменты аудио и видео.
Они входят только в androidTest assets и не увеличивают release APK.

В `ClipTransportPlaybackTest` production ClipMediaPreloader/Media3 читает
сегменты из файла на эмуляторе. В `ClipLongSessionTest` API явно вымышленный,
MediaSource.Factory заменяет адрес медиаресурса собственным MP4. Серверный
протокол, реальные ответы Яндекса и сеть этим тестом не подтверждаются.
