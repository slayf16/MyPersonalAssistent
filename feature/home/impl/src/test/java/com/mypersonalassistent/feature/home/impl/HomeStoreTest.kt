package com.mypersonalassistent.feature.home.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.home.api.HomeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStoreTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `retry recovers from failed observation to content`() = runTest(dispatcher) {
        val history = FailOnceHistory()
        val store = HomeStoreFactory(DefaultStoreFactory(), history).create()

        store.accept(HomeIntent.Retry)
        testScheduler.advanceUntilIdle()
        assertTrue(store.state.error)
        assertFalse(store.state.isLoading)

        store.accept(HomeIntent.Retry)
        testScheduler.advanceUntilIdle()

        assertFalse(store.state.error)
        assertFalse(store.state.isLoading)
        assertEquals(listOf(ChatSummary("chat-1", "Сохранённый чат", 1)), store.state.chats)
        assertEquals(2, history.observations)
        store.dispose()
    }

    @Test
    fun `restarting observation does not turn cancellation into an error`() = runTest(dispatcher) {
        val history = CancellableHistory()
        val store = HomeStoreFactory(DefaultStoreFactory(), history).create()

        store.accept(HomeIntent.Retry)
        testScheduler.advanceUntilIdle()
        store.accept(HomeIntent.Retry)
        testScheduler.runCurrent()

        assertTrue(store.state.isLoading)
        assertFalse(store.state.error)
        assertEquals(2, history.observations)
        store.dispose()
    }

    private class FailOnceHistory : HistoryRepository {
        var observations = 0
        override fun observeSummaries(): Flow<List<ChatSummary>> =
            if (observations++ == 0) flow { throw IllegalStateException("Room unavailable") }
            else flowOf(listOf(ChatSummary("chat-1", "Сохранённый чат", 1)))

        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot): Boolean = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean = true
    }

    private class CancellableHistory : HistoryRepository {
        var observations = 0
        override fun observeSummaries(): Flow<List<ChatSummary>> = flow {
            observations += 1
            awaitCancellation()
        }

        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot): Boolean = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean = true
    }
}
