plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mypersonalassistent.feature.mcpsettings.api"; compileSdk = 36; defaultConfig { minSdk = 26 } }
dependencies { api(project(":core:mcp:api")) }
