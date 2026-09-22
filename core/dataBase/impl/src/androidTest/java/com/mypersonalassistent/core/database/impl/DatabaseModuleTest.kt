package com.mypersonalassistent.core.database.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChat
import com.mypersonalassistent.core.database.api.StoredTaskMemory
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.stopKoin
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent

@RunWith(AndroidJUnit4::class)
class DatabaseModuleTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "task006-${System.nanoTime()}.db"

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
    }

    @After fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test fun databaseStorageIsOneKoinInstancePerGraph() {
        stopKoin()
        startKoin { androidContext(context); modules(databaseModule) }
        val first = KoinJavaComponent.get<ChatStorage>(ChatStorage::class.java)
        val second = KoinJavaComponent.get<ChatStorage>(ChatStorage::class.java)
        assertSame(first, second)
        stopKoin()
    }

    @Test fun migrationFromV1PreservesChatsAndCreatesMemoryTables() = kotlinx.coroutines.runBlocking {
        val legacy = context.openOrCreateDatabase(databaseName, android.content.Context.MODE_PRIVATE, null)
        legacy.execSQL(
            "CREATE TABLE chats (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, contextJson TEXT NOT NULL, PRIMARY KEY(id))"
        )
        legacy.execSQL(
            "INSERT INTO chats(id, title, createdAt, updatedAt, contextJson) VALUES " +
                "('legacy', 'Saved chat', 1, 2, '{\"schemaVersion\":1,\"messages\":[]}')"
        )
        legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        legacy.execSQL(
            "INSERT INTO room_master_table (id, identity_hash) VALUES " +
                "(42, 'b0dd0308bbf112602e030cf0b0bddb3f')"
        )
        legacy.version = 1
        legacy.close()

        val storage = RoomChatStorage.createForTesting(context, databaseName)
        assertEquals("Saved chat", storage.read("legacy")?.title)
        assertNull(storage.readTaskMemory("legacy"))
    }

    @Test fun migrationFromV2PreservesProfileAndAddsEmptyCustomValues() = kotlinx.coroutines.runBlocking {
        val legacy = context.openOrCreateDatabase(databaseName, android.content.Context.MODE_PRIVATE, null)
        legacy.execSQL(
            "CREATE TABLE chats (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, contextJson TEXT NOT NULL, PRIMARY KEY(id))"
        )
        legacy.execSQL(
            "CREATE TABLE agent_profile (id INTEGER NOT NULL, onboardingStatus TEXT NOT NULL, preferredName TEXT, " +
                "language TEXT NOT NULL, tone TEXT NOT NULL, detailLevel TEXT NOT NULL, customInstructions TEXT NOT NULL, " +
                "updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
        )
        legacy.execSQL(
            "CREATE TABLE task_memory (chatId TEXT NOT NULL, goal TEXT NOT NULL, constraintsJson TEXT NOT NULL, " +
                "desiredResult TEXT NOT NULL, decisionsJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(chatId), " +
                "FOREIGN KEY(chatId) REFERENCES chats(id) ON DELETE CASCADE)"
        )
        legacy.execSQL("CREATE INDEX index_task_memory_chatId ON task_memory (chatId)")
        legacy.execSQL("INSERT INTO chats VALUES ('legacy', 'Saved chat', 1, 2, '{}')")
        legacy.execSQL(
            "INSERT INTO agent_profile VALUES (1, 'COMPLETED', 'Alex', 'RUSSIAN', 'FRIENDLY', " +
                "'DETAILED', 'first summary', 3)"
        )
        legacy.execSQL("INSERT INTO task_memory VALUES ('legacy', 'goal', '[]', 'result', '[]', 4)")
        legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        legacy.execSQL(
            "INSERT INTO room_master_table (id, identity_hash) VALUES " +
                "(42, 'cad2aa66cca88beb1d0055149774e40b')"
        )
        legacy.version = 2
        legacy.close()

        val storage = RoomChatStorage.createForTesting(context, databaseName)
        val profile = storage.readProfile()
        assertEquals("Saved chat", storage.read("legacy")?.title)
        assertEquals("Alex", profile?.preferredName)
        assertEquals("", profile?.customLanguage)
        assertEquals("", profile?.customTone)
        assertEquals("", profile?.customDetailLevel)
        assertEquals("goal", storage.readTaskMemory("legacy")?.goal)
    }

    @Test fun failedBundleWriteRollsBackChatAndTaskMemoryTogether() = kotlinx.coroutines.runBlocking {
        val storage = RoomChatStorage.createForTesting(context, databaseName)
        val chat = StoredChat("new-chat", "New", 1, 2, "{}")
        val invalidTask = StoredTaskMemory("missing-chat", "goal", "[]", "", "[]", 2)

        assertEquals(StorageResult.Failure, storage.upsertWithTaskMemory(chat, invalidTask))
        assertNull(storage.read("new-chat"))
        assertNull(storage.readTaskMemory("missing-chat"))
    }
}
