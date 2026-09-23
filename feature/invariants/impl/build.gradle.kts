plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

android { namespace = "com.mypersonalassistent.feature.invariants.impl"; compileSdk = 36; defaultConfig { minSdk = 26 }; buildFeatures { compose = true } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation(project(":feature:invariants:api"))
    implementation(project(":core:invariants:api"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("io.insert-koin:koin-core:4.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.mvikotlin:mvikotlin:4.3.0")
    implementation("com.arkivanov.mvikotlin:mvikotlin-main:4.3.0")
    implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
