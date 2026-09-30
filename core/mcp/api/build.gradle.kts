plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android { namespace = "com.mypersonalassistent.core.mcp.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
