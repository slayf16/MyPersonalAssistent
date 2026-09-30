plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mypersonalassistent.core.llm.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
