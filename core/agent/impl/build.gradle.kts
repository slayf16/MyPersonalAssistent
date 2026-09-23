plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android { namespace = "com.mypersonalassistent.core.agent.impl"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies {
    implementation(project(":core:agent:api")); implementation(project(":core:memory:api"))
    implementation(project(":core:history:api")); implementation(project(":core:llm:api"))
    implementation("io.insert-koin:koin-core:4.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2"); testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
