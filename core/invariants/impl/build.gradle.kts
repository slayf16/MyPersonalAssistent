plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.mypersonalassistent.core.invariants.impl"; compileSdk = 36; defaultConfig { minSdk = 26 } }

dependencies {
    implementation(project(":core:invariants:api"))
    implementation(project(":core:dataBase:api"))
    implementation(project(":core:llm:api"))
    implementation("io.insert-koin:koin-core:4.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
