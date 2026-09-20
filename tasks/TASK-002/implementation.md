Outcome: PASS

# TASK-002 — реализация, CODING cycle 2/2

Дата: 2026-09-20. Это авторский отчёт реализации; независимое ревью и формальное тестирование ещё не выполнялись. Утверждённые `specs.md` и `state.json` не менялись.

## Выполнено

- Закрыт R1 из независимого review: `HomeStore` различает loading, error, empty и content. На ошибке `observeSummaries()` публикуется однократный `HomeEffect.TechnicalError`; `MainActivity` показывает по нему единственный snackbar «Техническая ошибка». `CancellationException` пробрасывается при отмене collector и не меняет состояние на error.
- `HomeScreen` отображает индикатор loading, отдельный текст ошибки и действие «Повторить», которое отправляет `HomeIntent.Retry`; empty-state отображается только после успешного пустого результата. Список чатов расположен под header «Сменить ключ» и имеет нижний отступ под FAB.
- Добавлен `HomeStoreTest`: восстановление `error -> Retry -> content` и отсутствие ложной ошибки при отмене предыдущего collector. Добавлен API 36 Compose-тест `HomeUiTest`: error-state не маскируется под empty-state, Retry передаёт intent, после обновления state отображается сохранённый чат.
- По дополнительному finding QA `DeepSeekLlm` классифицирует malformed JSON для HTTP 200 как `LlmError.INVALID_RESPONSE` и при прямом `SerializationException`, и при Ktor `JsonConvertException`; сетевые и cancellation пути не изменены. QA дополнил `DeepSeekLlmTest` регрессией malformed response, mapping HTTP ошибок, timeout и cancellation.

## Изменённые файлы

- `feature/home/api/.../HomeFeature.kt`
- `feature/home/impl/.../HomeStore.kt`, `HomeScreen.kt`, `build.gradle.kts`, новый `HomeStoreTest.kt`
- `app/.../MainActivity.kt`, новый `HomeUiTest.kt`
- `core/llm/impl/.../DeepSeekLlm.kt`
- `core/llm/impl/.../DeepSeekLlmTest.kt` — изменён QA, автор этого отчёта его не редактировал.

## Фактические проверки

| Проверка | Итог | Доказательство |
|---|---|---|
| Статический поиск effect/retry/cancellation/INTERNET | PASS | `rg` подтвердил effect в Store и app wiring, Retry в UI, явную обработку cancellation и manifest permission. |
| Поиск `GlobalScope` и `fallbackToDestructiveMigration` | PASS | Совпадений в `app`, `core`, `feature` нет. |
| Проверка пробелов tracked diff | PASS | `git -c safe.directory=... diff --check` не сообщил ошибок в производственных tracked изменениях; предупреждение относится к ранее изменённому `docs/OPEN-QUESTIONS.md`, не к этой реализации. |
| `:feature:home:impl:testDebugUnitTest :core:llm:impl:testDebugUnitTest` | PASS, 9 tests | `HomeStoreTest`: 2/0 failures/0 errors; `DeepSeekLlmTest`: 7/0/0, включая malformed HTTP 200 и cancellation. XML в `feature/home/impl/build/test-results/...` и `core/llm/impl/build/test-results/...`; Gradle-задачи завершились до последующей Android-test ошибки. |
| `:app:assembleDebug` | PASS | `:app:assembleDebug` завершилась в `tasks/TASK-002/cycle2-build-2.log`; APK `app/build/outputs/apk/debug/app-debug.apk`, 14,610,398 bytes, 2026-09-20 08:32:44. |
| `:app:compileDebugAndroidTestKotlin` | PENDING | В `cycle2-build-2.log` task дошёл до compile и остановился на устаревшем import `assertDoesNotExist`; import удалён после лога. Координатор повторяет compile. |
| `:app:connectedDebugAndroidTest` | PENDING formal TESTING | Новый `HomeUiTest` ещё не выполнялся на API 36. Это не препятствует статической валидации инвариантов; фактический runtime результат оформляет QA на этапе TESTING. |
| Live DeepSeek/API 26 | N/A | По решениям заказчика использовать mocks; API 26 не запускать. |

## Блокер и следующий шаг

Целевые unit tests и APK подтверждены; предварительная реализация завершена. Координатор повторяет `:app:compileDebugAndroidTestKotlin` после удаления import, затем передаёт QA APK/новый `HomeUiTest` для API 36. Runtime QA остаётся PENDING отдельного этапа TESTING и не объявляется успешной в этом отчёте. Production и тестовые файлы заморожены для независимого review.
