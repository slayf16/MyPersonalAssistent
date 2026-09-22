plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mypersonalassistent"
    compileSdk = 36
    defaultConfig { applicationId = "com.mypersonalassistent"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("io.insert-koin:koin-android:4.0.2")
    implementation("io.insert-koin:koin-androidx-compose:4.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.decompose:extensions-compose:3.3.0")
    implementation(project(":core:credentials:impl")); implementation(project(":core:dataBase:impl")); implementation(project(":core:history:impl")); implementation(project(":core:llm:impl")); implementation(project(":core:memory:impl")); implementation(project(":core:agent:impl"))
    implementation(project(":feature:credentials:impl")); implementation(project(":feature:home:impl")); implementation(project(":feature:chat:impl")); implementation(project(":feature:profile:impl"))
    implementation(project(":feature:credentials:api")); implementation(project(":feature:home:api")); implementation(project(":feature:chat:api")); implementation(project(":feature:profile:api"))
    implementation(project(":core:credentials:api")); implementation(project(":core:history:api")); implementation(project(":core:llm:api")); implementation(project(":core:memory:api")); implementation(project(":core:agent:api"))
}
