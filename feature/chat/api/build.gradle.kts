plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mypersonalassistent.feature.chat.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies { api(project(":core:agent:api")); api(project(":core:history:api")); api(project(":core:memory:api")) }
