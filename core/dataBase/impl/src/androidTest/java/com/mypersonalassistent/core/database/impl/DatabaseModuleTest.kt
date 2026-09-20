package com.mypersonalassistent.core.database.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.database.api.ChatStorage
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.stopKoin
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent

@RunWith(AndroidJUnit4::class)
class DatabaseModuleTest {
    @Test fun databaseStorageIsOneKoinInstancePerGraph() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        startKoin { androidContext(context); modules(databaseModule) }
        val first = KoinJavaComponent.get<ChatStorage>(ChatStorage::class.java)
        val second = KoinJavaComponent.get<ChatStorage>(ChatStorage::class.java)
        assertSame(first, second)
        stopKoin()
    }
}
