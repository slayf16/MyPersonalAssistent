package com.mypersonalassistent.core.history.impl

import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.AgentStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChat
import com.mypersonalassistent.core.database.api.StoredChatSummary
import com.mypersonalassistent.core.database.api.StoredTaskMemory
import com.mypersonalassistent.core.database.api.StoredAgentCheckpoint
import com.mypersonalassistent.core.database.api.StoredAgentRecovery
import com.mypersonalassistent.core.database.api.StoredRecoverySummary
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.DeliveryState
import com.mypersonalassistent.core.history.api.HistoryCorruptionException
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.memory.api.TaskMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RoomHistoryRepositoryTest {
    @Test fun saveThenReadRoundTripsMessagesWithoutExposingRoom() = runBlocking {
        val storage = FakeStorage(); val repository = RoomHistoryRepository(storage, storage)
        val snapshot = ChatSnapshot("id", "title", 1, 2, listOf(ChatMessage("m", MessageRole.USER, "text", DeliveryState.COMPLETE)))
        assertEquals(true, repository.save(snapshot))
        assertEquals(snapshot, repository.read("id"))
        assertEquals("id", storage.last?.id)
    }
    @Test fun unknownSchemaIsRejectedAndNotRewritten() = runBlocking {
        val storage = FakeStorage(StoredChat("id", "title", 1, 2, "{\"schemaVersion\":2,\"messages\":[]}")); val repository = RoomHistoryRepository(storage, storage)
        try {
            repository.read("id")
            fail("Unsupported persisted history must be reported as corruption")
        } catch (_: HistoryCorruptionException) {
            assertNull(storage.last)
        }
    }
    @Test fun chatAndTaskMemoryUseOneBundleWrite() = runBlocking {
        val storage = FakeStorage(); val repository = RoomHistoryRepository(storage, storage)
        val snapshot = ChatSnapshot("id", "title", 1, 2, emptyList())
        val task = TaskMemory("id", "goal", listOf("one"), "done", listOf("decision"), 2)
        assertEquals(true, repository.save(snapshot, task))
        assertEquals("goal", storage.lastTask?.goal)
        assertEquals("[\"one\"]", storage.lastTask?.constraintsJson)
        assertEquals("[\"decision\"]", storage.lastTask?.decisionsJson)
    }
    private class FakeStorage(initial: StoredChat? = null) : ChatStorage, AgentStorage {
        var value = initial; var last: StoredChat? = null; var lastTask: StoredTaskMemory? = null
        override fun observeSummaries(): Flow<List<StoredChatSummary>> = emptyFlow()
        override suspend fun read(id: String): StoredChat? = value
        override suspend fun upsert(chat: StoredChat): StorageResult { last = chat; value = chat; return StorageResult.Success }
        override suspend fun upsertWithTaskMemory(chat: StoredChat, taskMemory: StoredTaskMemory?): StorageResult { lastTask = taskMemory; return upsert(chat) }
        override fun observeRecoverySummaries(): Flow<List<StoredRecoverySummary>> = emptyFlow()
        override suspend fun readAgentCheckpoint(chatId: String): StoredAgentCheckpoint? = null
        override suspend fun readAgentRecovery(chatId: String): StoredAgentRecovery? = null
        override suspend fun writeAgentRecovery(recovery: StoredAgentRecovery): StorageResult = StorageResult.Success
        override suspend fun promoteAgentRecovery(recovery: StoredAgentRecovery): StorageResult = StorageResult.Success
        override suspend fun discardAgentRecovery(chatId: String): StorageResult = StorageResult.Success
    }
}
