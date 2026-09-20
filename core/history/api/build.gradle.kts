plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mypersonalassistent.core.history.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
