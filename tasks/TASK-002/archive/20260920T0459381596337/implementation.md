Outcome: PASS

# TASK-002 — реализация, CODING cycle 1/2

Дата: 2026-09-20. Это авторский отчёт реализации, не независимое code review. Все предусмотренные для CODING проверки имеют фактические PASS на target API 36; live DeepSeek smoke является N/A по явному решению заказчика.

## Реализовано

- Создана утверждённая структура из 15 Gradle-модулей: `app`; пары `api/impl` для `core:credentials`, `core:dataBase`, `core:history`, `core:llm`; пары `api/impl` для `feature:credentials`, `feature:home`, `feature:chat`.
- API содержат только нейтральные контракты и immutable State/Intent/Effect. Room entity/DAO/DB остаются в `core:dataBase:impl`, DeepSeek DTO и Ktor-клиент — в `core:llm:impl`.
- `SecureCredentialRepository` хранит API-ключ через Android Keystore AES-GCM: свежий IV на каждую запись, private preferences и исключение файла ключа из backup/transfer. Ключ не включён в БД, исходники или BuildConfig.
- `core:dataBase:impl` создаёт единственный `ChatStorage` через Koin `single`; destructive migration не используется. `core:history:impl` хранит JSON schema v1, сериализует/читает на внедряемом IO dispatcher и сообщает повреждённый либо неизвестный JSON как ошибку без перезаписи.
- `DeepSeekLlm` реализует `Llm.execute`: POST на `/chat/completions`, DI-конфигурация модели, `model`, `stream=false`, `thinking.type=disabled`, `max_tokens=2048`, лимит контекста 1 MiB, таймауты и доменные ошибки. `CancellationException` не преобразуется в пользовательскую ошибку; manifest объявляет `android.permission.INTERNET`.
- `DefaultRootComponent` использует serializable Decompose `childStack` для Credentials/Home/Chat. Store каждого feature удерживается через `InstanceKeeper` при повороте. Все три feature реализованы MVIKotlin Store с reducer и CoroutineExecutor; Compose получает StateFlow и посылает Intent, одноразовые labels управляют snackbar и навигацией.
- Home содержит список тем и действие замены ключа. Chat реализует различимые M3-пузыри ролей, один анимированный assistant typing bubble, accessibility label, IME-safe input и системный Back с диалогом сохранения.
- Chat Store блокирует повторную отправку, работу до завершения чтения и отправку после ошибки чтения; сохраняет исходный draft без trim; использует requestId против поздних callback. Ошибка LLM возвращает точный текст и удаляет только неуспешный turn. «Да» выполняет cancel-and-join, сохраняет interrupted user turn и навигирует только после успешного upsert; сбой записи снимает `saving`, оставляет transcript и даёт повторить. «Нет» не записывает рабочую копию.
- README и `docs/DEPENDENCIES.md` обновлены отдельным build-исполнителем; AGP допускается на основании явного решения в `docs/decisions/2026-09-19-agp-source-exception.md`.

## Фактически выполненные проверки

Контрольные Gradle-прогоны выполнялись на JBR 21, SDK/AVD `New_Device` API 36.

| Проверка | Итог | Доказательство |
|---|---|---|
| `:app:assembleDebug` | PASS | Собран `app-debug.apk` размером 14.6 MB; контрольный лог `tasks/TASK-002/assemble-control-7.log`. |
| JUnit `testDebugUnitTest` | PASS, 10 тестов | Chat Store 5, History 2, DeepSeek MockEngine 3; итог контрольного прогона сохранён в `tasks/TASK-002/pre-review-tests.log`. |
| DeepSeek adapter | PASS без сети | MockEngine проверяет Authorization, success mapping, 401 mapping, preflight >1 MiB и payload `model/stream/thinking/max_tokens`. Реальный smoke N/A: заказчик указал «тестируй на моках , дипсик потом сам подключу проверю». |
| target API 36: credentials | PASS, 1 instrumentation test | Keystore AES-GCM roundtrip, ciphertext отличается от ключа, IV меняется; `tasks/TASK-002/pre-review-android-tests.log`. |
| target API 36: dataBase | PASS, 1 instrumentation test | два разрешения Koin возвращают один `ChatStorage`; тот же лог. |
| target API 36: app Compose | PASS, 3 instrumentation tests | `ChatUiTest`: waiting/disabled input, error restore + snackbar, system Back + save dialog. QA XML 2026-09-20 01:18:27: 3 tests, 0 failures, 0 errors. |
| статические запреты | PASS | Поиск не нашёл `GlobalScope`, `fallbackToDestructiveMigration`, Room/Ktor import в API; результаты добавлены в `invariants.md`. |

API 26 device test N/A по явному отказу заказчика; `minSdk 26` остаётся в продукте. Ранние failures compile/runtime при подготовке `ChatUiTest` исправлены до финального прогона и не считаются успешными вместо него.

## Следующий шаг

Передать код независимому code reviewer. Production-код заморожен; исправления только по findings или диагностике review.
