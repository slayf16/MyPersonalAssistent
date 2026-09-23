package com.mypersonalassistent.feature.home.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.AgentRecoverySummary
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.home.api.HomeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `observation exposes unsaved recovery and saved chat marker data`() = runTest(dispatcher) {
        val recovery = FakeRecovery(listOf(AgentRecoverySummary("new", false, 2), AgentRecoverySummary("saved", true, 3)))
        val store = HomeStoreFactory(DefaultStoreFactory(), FakeHistory, recovery).create()
        store.accept(HomeIntent.Retry); testScheduler.advanceUntilIdle()
        assertEquals(listOf(ChatSummary("saved", "Сохранённый", 1)), store.state.chats)
        assertEquals(recovery.items, store.state.recovery)
        store.dispose()
    }

    @Test fun `discard recovery delegates only the selected draft`() = runTest(dispatcher) {
        val recovery = FakeRecovery(emptyList())
        val store = HomeStoreFactory(DefaultStoreFactory(), FakeHistory, recovery).create()
        store.accept(HomeIntent.DiscardRecovery("new")); testScheduler.advanceUntilIdle()
        assertTrue(recovery.discarded.contains("new"))
        store.dispose()
    }

    private object FakeHistory : HistoryRepository {
        override fun observeSummaries(): Flow<List<ChatSummary>> = flowOf(listOf(ChatSummary("saved", "Сохранённый", 1)))
        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot) = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true
    }
    private class FakeRecovery(val items: List<AgentRecoverySummary>) : AgentRecoveryRepository {
        val discarded = mutableListOf<String>()
        override fun observeRecovery(): Flow<List<AgentRecoverySummary>> = flowOf(items)
        override suspend fun readCheckpoint(chatId: String): String? = null
        override suspend fun readRecovery(chatId: String): AgentRecovery? = null
        override suspend fun writeRecovery(recovery: AgentRecovery) = true
        override suspend fun promoteRecovery(recovery: AgentRecovery) = true
        override suspend fun discardRecovery(chatId: String): Boolean { discarded += chatId; return true }
    }
}
