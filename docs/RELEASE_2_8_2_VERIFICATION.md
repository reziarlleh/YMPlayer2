# Выпуск YMPlayer2 2.8.2-build148

17 ноября 2026 владелец поручил GitHub → одно сообщение4PDA → свежую шапку.
Публикуется существующий APK без пересборки:4763189 bytes,
SHA256 `39560d85c0a43a9a7f9ce6fe4f2edd99a2902ec7abf8ba90bd3fef0a2a7d4eb8`. Пакет dev.petrov.ymplayer2, min23/target36,
четыре ABI, подпись v1+v2 прежним сертификатом
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
[Полный состав](RELEASE_2_8_2_NOTES.md), [план](PLAN_2_8_2.md).

## Проверенное до публикации

Исходники продукта `36b13f16c10fa5d75cdffc799b91e546aec00630`, CI44069457433 success.
Режимы старта:190 JVM,31 native на каждой23/35,6 диагностических и9 radio
на каждой API, signed145→147 и холодные сценарии режима —
[матрица147](PLAYBACK_START_2_8_2_VERIFICATION.md).
Исправление148: по7 input и3 lifecycle сценария на23/35, signed147→148
с сохранением UID/данных/профиля/режима, открытие без Java-падения —
[матрица148](CLIP_FOCUS_2_8_2_VERIFICATION.md).
Сохранены расширенная диагностика146 и восемь переводов.
Разрешение выпуска не является новой физической проверкой TV/ГУ или закрытием D-004/D-009.

## Публикация

GitHub regular release опубликован и выбран Latest. Четыре assets совпали
по digest/размеру; исходный v-tag и минимальный apk-tag созданы впервые,
удалённые ссылки сверены. [GitHub](qa/release282/github.json),
[immutable distribution](qa/release282/distribution-tag.json).
Оба source feed148/min23; далее проверяются HTTP-доставка, CDN и4PDA.
