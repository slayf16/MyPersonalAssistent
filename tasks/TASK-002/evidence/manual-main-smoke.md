# TASK-002 — manual smoke настоящего MainActivity

Дата: 2026-09-20. Это предварительное доказательство до формального `TESTING`;
не заменяет итоговый отчёт `tests.md`.

## Среда

- AVD `New_Device`, `emulator-5554`, Android API 36, Google APIs x86_64, 360x640.
- Установлен существующий debug APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk` — `Success`.
- Launcher запущен явно: `adb shell am start -W -n com.mypersonalassistent/com.mypersonalassistent.app.MainActivity` — `Status: ok`.

Использован только синтетический тестовый маркер в форме ключа. Настоящий ключ,
отправка сообщения, DeepSeek и сеть не использовались.

## Наблюдения

1. После сохранения синтетического маркера открыт Home с FAB `Создать чат`.
2. Открыт пустой чат. Реальный системный Back (`adb shell input keyevent 4`) показал модалку `Сохранить чат?` с действиями `Нет` и `Да`.
3. Выбор `Нет` вернул на Home с текстом пустой истории; записи чата не было.
4. Для нового пустого чата выбор `Да` добавил на Home `Новый чат`.
5. После `adb shell am force-stop com.mypersonalassistent` и повторного явного запуска `MainActivity` строка `Новый чат` сохранилась. Это подтверждает базовую связку navigation/Room для подтверждённого пустого чата.
6. В третьем несохранённом чате введён неотправленный тестовый черновик `rotation-draft`. После фактической смены ориентации (dump: `rotation=1`) значение осталось в `EditText`. Исходные настройки ориентации AVD (`accelerometer_rotation=1`, `user_rotation=0`) восстановлены.

## Файлы доказательств

- `api36-chat-empty.png` — настоящий пустой Chat UI.
- `api36-save-dialog.png` — настоящая модалка системного Back.
- `api36-home-saved-chat.png` — Home после `Да`; повторный UI dump после force-stop/relaunch также содержал `Новый чат`.

Скриншоты и этот журнал не содержат ключа, HTTP-заголовков или пользовательских сообщений.
