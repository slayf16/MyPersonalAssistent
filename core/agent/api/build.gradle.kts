plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mypersonalassistent.core.agent.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies {
    api(project(":core:llm:api")); api(project(":core:history:api")); api(project(":core:memory:api")); api(project(":core:invariants:api"))
    testImplementation("junit:junit:4.13.2")
}
