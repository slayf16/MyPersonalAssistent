plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }

android { namespace = "com.mypersonalassistent.feature.invariants.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }

dependencies {
    api(project(":core:invariants:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
