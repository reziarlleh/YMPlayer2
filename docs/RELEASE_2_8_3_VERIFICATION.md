# Выпуск YMPlayer2 2.8.3-build150

18 ноября 2026 владелец разрешил публикацию GitHub и 4PDA. Выпущен APK, который
прошёл [аудит и проверку обновления с 148](MANUAL_2_8_3_BUILD150.md).
[Состав версии](RELEASE_2_8_3_NOTES.md).

GitHub выбрал обычный [релиз 2.8.3-build150](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.8.3-build150) как Latest.
[Четыре файла релиза](qa/release283/github.json) проверены по размеру и SHA256.
APK с GitHub, jsDelivr и Gcore полностью скачаны и совпали с исходным файлом.
[Проверка загрузок](qa/release283/downloads.json) · [теги](qa/release283/distribution-tag.json).
Оба JSON автообновления обновлены после проверки APK.
Пять из шести адресов отдают 150, включая все три `manifest.json`. Gcore `stable.json` на момент проверки отдавал 2.8.2-build148.
[HTTP-проверка от 18 ноября](qa/release283/feeds.json) · [обновление кэша jsDelivr](qa/release283/cdn-purge.json).

На 4PDA опубликовано [сообщение №126](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=146070404) с APK. Владелец отредактировал текст поста.
Шапка теперь с 2.8.3, Android 6.0+ и APK 150. Предыдущий 148 доступен по ссылке
на его пост. Копии APK с форума совпали с подписанными файлами.
[Проверка публикации и шапки](FORUM_RELEASE_2_8_3_2026_11_18.md).

README, обе инструкции, CHANGELOG и проектные журналы согласованы с выпуском.
Исходники продукта соответствуют [успешному CI 44227873929](https://github.com/reziarlleh/YMPlayer2/actions/runs/44227873929).
Подписанное обновление 148→150 проверено на API 23/35 в ходе аудита.
D-011 остаётся открытым для старого системного MediaBrowser на Android 6.
