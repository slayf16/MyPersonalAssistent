# Реестр внешних зависимостей и заимствованного кода

Правило заказчика: только открытые GitHub-репозитории с не менее чем 30 звёздами.
Число проверять онлайн до добавления и обновления; фиксировать дату и ссылку.
Открытая страница без лицензии не даёт основания копировать код.
При отсутствии доступа или проверяемого источника не добавлять зависимость.
Форки проверять как фактический источник, не переносить число звёзд upstream.

| Artifact/код | Версия/commit | Исходный репозиторий | Звёзды | Дата | Лицензия | Прямой/транзитивный | Решение/доказательство |
|---|---|---|---|---|---|---|---|
| Kotlin Gradle plugin, Compose compiler plugin, stdlib и serialization | 2.2.21 / 1.8.1 | https://github.com/JetBrains/kotlin | 53.4k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: публичный репозиторий, GitHub отображает 53.4k; license в README. Compose compiler plugin обязателен для Kotlin 2.x при включённом Compose. |
| AndroidX: Activity Compose, Compose BOM/UI/M3/ui-test, Room | 1.10.1 / 2025.08.01 / 2.8.3 | https://github.com/androidx/androidx | 6.1k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub показывает Apache-2.0 и 6.1k stars; репозиторий прямо описывает поставку AndroidX AAR через Google Maven. Compose BOM также управляет ui-test-junit4/ui-test-manifest; Room 2.8.3 выбран после KSP processor failure в 2.6.1. |
| AndroidX Test: core, ext:junit, runner | 1.6.1 / 1.2.1 / 1.6.2 | https://github.com/android/android-test | 1.2k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub показывает Apache-2.0 и 1.2k stars; README подтверждает координаты androidx.test:core, androidx.test.ext:junit и androidx.test:runner. |
| kotlinx.coroutines: core, android, test | 1.10.2 | https://github.com/Kotlin/kotlinx.coroutines | 13.8k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub page показывает 13.8k и Apache-2.0; test artifact используется для Llm и History unit tests. |
| Koin | 4.0.2 | https://github.com/InsertKoinIO/koin | 10.0k (округлённое отображаемое GitHub) | 2026-09-19 | Apache-2.0 | прямой | Допущено: GitHub page показывает 10.0k и Apache-2.0. |
| Decompose: decompose, extensions-compose | 3.3.0 | https://github.com/arkivanov/Decompose | 2.9k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub показывает Apache-2.0 и 2.9k; официальная документация устанавливает эти координаты. Gradle resolve скачал Android-варианты. |
| MVIKotlin: main, extensions-coroutines | 4.3.0 | https://github.com/arkivanov/MVIKotlin | 1.0k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub показывает Apache-2.0 и 1.0k; README определяет main как default Store и отдельный coroutines extension. Gradle resolve скачал Android-варианты. |
| Ktor: client core/OkHttp/content-negotiation/serialization/mock | 3.3.2 | https://github.com/ktorio/ktor | 14.5k (округлённое отображаемое GitHub) | 2026-09-20 | Apache-2.0 | прямой | Допущено: GitHub page показывает 14.5k и Apache-2.0; `ktor-client-mock` используется для unit tests DeepSeekLlm без сети. |
| JUnit 4 | 4.13.2 | https://github.com/junit-team/junit4 | 8.5k (округлённое отображаемое GitHub) | 2026-09-19 | EPL-1.0 | прямой | Допущено: GitHub page показывает 8.5k и EPL-1.0. |
| KSP | 2.2.21-2.0.4 | https://github.com/google/ksp | 3.5k (округлённое отображаемое GitHub) | 2026-09-19 | Apache-2.0 | прямой | Допущено: GitHub page показывает 3.5k; лицензия Apache-2.0 указана в repository LICENSE. |
| Gradle Wrapper | 8.13 | https://github.com/gradle/gradle | 18k+ (округлённое отображаемое GitHub) | 2026-09-19 | Apache-2.0 | прямой | Допущено: публичный upstream Gradle, Apache-2.0; используется локально установленный дистрибутив. |
| Android Gradle Plugin | 8.13.2 | официально https://android.googlesource.com/platform/tools/base | GitHub-источник не найден | 2026-09-19 | Apache-2.0 (официальный исходный код) | прямой | Допущено явным исключением заказчика: [решение](decisions/2026-09-19-agp-source-exception.md) от 2026-09-19 для официального AGP из Google Maven; исключение не распространяется на другие зависимости. |

Все перечисленные прямые зависимости добавлены в Gradle-скрипты TASK-002. Для Decompose и MVIKotlin resolve подтверждён локальным Gradle-кэшем; успешная сборка и тесты фиксируются отдельно в отчётах задачи. По ответу заказчика от 2026-09-19 правило применяется к прямым зависимостям и заимствованным реализациям. Транзитивные зависимости не проверяются.
Не применять порог популярности к собственному коду этого проекта.
