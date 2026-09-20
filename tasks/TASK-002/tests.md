Outcome: PASS

# TASK-002 — формальное TESTING, цикл 2/2

Дата: 2026-09-20. Выполнено после `CODE_REVIEW` с PASS. Проверки проводились
на API 36 AVD `New_Device` (`emulator-5554`, Google APIs x86_64, 360x640),
JBR 21.0.8 и Android SDK `C:\Users\AMK29\AppData\Local\Android\Sdk`.
Реальный DeepSeek не вызывался: решение заказчика — mock/fake only. API 26 N/A
по решению заказчика о проверке только target API.

## Автоматические проверки

| Набор | Команда / доказательство | Фактический результат | AC |
|---|---|---|---|
| APK и unit | `:app:assembleDebug testDebugUnitTest`, `tasks/TASK-002/cycle2-build-2.log`; APK `app/build/outputs/apk/debug/app-debug.apk` | PASS для задач: APK создан (14,610,398 bytes, 08:32); 20 unit tests, 0 failures/errors: history 2, DeepSeek 7, Chat 9, Home 2. Общий ранний invocation завершился FAIL только на устаревшем import в androidTest после успешных указанных задач; import исправлен до следующего прогона. | AC2–AC8 |
| API 36 instrumentation | `:app:connectedDebugAndroidTest :core:credentials:impl:connectedDebugAndroidTest :core:dataBase:impl:connectedDebugAndroidTest`; [cycle2-android-tests.log](cycle2-android-tests.log) | PASS, `BUILD SUCCESSFUL` (1m35s). Свежие XML: app 4/0 failures/0 errors (Chat UI 3, Home UI 1); credentials 1/0/0; database 1/0/0. | AC1–AC4, AC7 |

Покрытие mock/unit: DeepSeek request (endpoint/payload/Authorization без вывода
ключа), 401, 402, 429, 5xx, malformed HTTP 200, timeout, cancellation и
лимит контекста; Chat Store — двойная отправка, возврат exact draft при ошибке,
повтор без дублирования, cancel/discard, interrupted save и retry записи;
Home — `error -> Retry -> content` и отмена collector; история — JSON round-trip
и отказ от неизвестной schema; Room singleton и cipher/новый IV проверены
инструментально.

## Ручной smoke реального MainActivity

Прогон выполнен на том же AVD с текущим debug APK. Перед началом очищены данные
именно пакета `com.mypersonalassistent`; использован синтетический маркер, не
являющийся API-ключом. Значения маркера, сообщений и HTTP-заголовков в лог и
артефакты не записывались.

| ID | Действия и факт | Результат | AC |
|---|---|---|---|
| M1 | После чистой установки launcher открыл форму «Ключ DeepSeek API». Поле имеет password semantics; `Сохранить` disabled при пустом значении. | PASS | AC1 |
| M2 | Синтетический непустой маркер сохранён в UI. Открыт Home с точным текстом «Можете начать создавать своего ассистента.» и FAB «Создать чат»; форма ключа не показана. | PASS | AC1, AC2 |
| M3 | FAB открыл новый чат. Реальный системный Back показал «Сохранить чат?» и оба действия «Нет»/«Да». «Да» сохранило пустой чат «Новый чат»; после `am force-stop` и явного launcher-start запись присутствовала на Home. | PASS | AC5, AC6 |

Дополнительное pre-review evidence от 2026-09-20 сохранено в
[manual-main-smoke.md](evidence/manual-main-smoke.md): «Нет» отбросило новый
пустой чат, «Да» сохранило «Новый чат», а фактический поворот сохранил
несохранённый `rotation-draft`. Это evidence относится к неизменённым Chat/Room
сценариям и не заменяет новый запуск выше.

## Критерии и ограничения

| Критерий | Доказательство | Итог |
|---|---|---|
| AC1, AC7 — ключ и защита | M1–M2; credentials instrumentation: ciphertext не равен исходному значению, повторная запись меняет IV; backup и отсутствие plaintext/Room подтверждены статически в PASS review. | PASS |
| AC2 — Home | M2; API 36 Home UI test: error не маскируется под empty, Retry передаёт intent и приводит к content; Home Store unit test подтверждает recovery/cancellation. | PASS |
| AC3, AC4 — чат | API 36 Chat UI tests и 9 Chat Store unit tests: typing/input lock, один snackbar в fake-error fixture, exact draft и повтор без дубля. | PASS |
| AC5, AC6 — save/discard/lifecycle | M3; pre-review M «Нет» и rotation evidence; Chat Store tests проверяют discard, interrupted turn и ошибку/retry save. | PASS |
| AC8 — Llm/DeepSeek контракт | 7 mock DeepSeek unit tests, включая response/error/cancellation mapping; реальная сеть N/A по решению заказчика. | PASS (mock scope) |
| AC9 — README | Независимый PASS code review: README сверён с модулями, Llm binding, DB, командами и save/discard behavior. | PASS (static) |

Не выполнялись и не требуются пользовательским решением: live DeepSeek, реальный
ключ и API 26. Расширенные exploratory cases (например TalkBack, крупный шрифт,
тёмная тема и ручной full-stack fault injection Room) не выполнялись; они не
выявили блокирующего дефекта в пределах утверждённых acceptance criteria и
автоматического API 36 покрытия.
