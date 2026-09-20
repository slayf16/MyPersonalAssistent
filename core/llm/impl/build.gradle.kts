plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android {
    namespace = "com.mypersonalassistent.core.llm.impl"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(project(":core:llm:api"))
    implementation(project(":core:credentials:api"))
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-okhttp:3.3.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.insert-koin:koin-core:4.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.3.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("io.ktor:ktor-client-mock:3.3.2")
}
