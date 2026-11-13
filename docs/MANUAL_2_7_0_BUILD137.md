# YMPlayer2 2.7.0-build137 — подписанный APK

13 ноября2026. Подписанный универсальный APK содержит весь цикл Android6+
и исправление возврата к Моей волне после другого источника (B-048).
Код main a2afe1c, [CI43401951128 success](https://github.com/reziarlleh/YMPlayer2/actions/runs/43401951128).
Владелец принял137 и поручил обычную публикацию13 ноября. Продвинут тот же
проверенный файл без пересборки; [выпуск и доставка](RELEASE_2_7_0_VERIFICATION.md).

- Файл: `releases/2.7.0-build137/YMPlayer-2.7.0-build137.apk`,4 664 183 байта.
- Пакет `dev.petrov.ymplayer2`, versionCode137, minSDK23, targetSDK36.
- Прежний сертификат, подписи v1+v2:
  `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
- SHA-256 APK:
  `6629c31ef15fc500eb0f158bd140c0607975b060681f116003f6896463854807`.
- [Полный состав2.7.0](RELEASE_2_7_0_NOTES.md), [план](PLAN_2_7_0.md),
  [B-048 и native red/green](WAVE_SOURCE_RETURN_2_7_0_VERIFICATION.md),
  [исходная матрица Android6+](ANDROID6_2_7_0_VERIFICATION.md).

По73 уникальных native-сценария на API23/API35 с адресным повтором одного
обновлённого ожидания теста; JVM164+4, lint0 errors/0NewApi/130 warnings.
На API35 дополнительно3 двухпроцессных сценария noisy/notification dismiss/
controller Stop/явный TV Stop. Signed136→137 на23/35 сохранил UID,
firstInstallTime и приватный контрольный файл; запуск остаётся в MainActivity.
[Подпись, хэш и upgrade](qa/wave-source270/artifact.json).

Устанавливать поверх текущей2.x без удаления. Пакет, подпись и данные сохранены.
На Android6/7 используется общий переключатель «Неизвестные источники»,
на8+ — разрешение источнику APK. Подтверждение установки остаётся у Android.
Физические устройства/персональный аккаунт этим прогоном не проверялись.

Stable/Latest/оба update feed — **2.7.0-build137**, min23.
[GitHub Release](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.7.0-build137).
[Сообщение4PDA№109 и шапка](FORUM_RELEASE_2_7_0_2026_11_13.md) опубликованы
после проверки GitHub/зеркал. Оба форумных APK полностью сверены с оригиналом.
