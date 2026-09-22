plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android { namespace = "com.mypersonalassistent.core.memory.impl"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies {
    implementation(project(":core:memory:api")); implementation(project(":core:dataBase:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.insert-koin:koin-core:4.0.2")
    testImplementation("junit:junit:4.13.2"); testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
