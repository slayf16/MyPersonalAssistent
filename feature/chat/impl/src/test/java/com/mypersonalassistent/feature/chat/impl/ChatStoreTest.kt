package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmError
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.llm.api.LlmMessage
import com.mypersonalassistent.core.llm.api.LlmRole
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.chat.api.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    @Test fun `failed send restores exact raw draft and removes only current turn`() = runTest(dispatcher) {
        val store = store(NoHistory, FailingLlm)
        store.accept(ChatIntent.ChangeDraft("  exact text  ")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals("  exact text  ", store.state.draft)
        assertEquals(0, store.state.messages.size)
        store.dispose()
    }
    @Test fun `concurrent send creates one provider call`() = runTest(dispatcher) {
        val llm = CountingLlm(); val store = store(NoHistory, llm)
        store.accept(ChatIntent.ChangeDraft("one")); store.accept(ChatIntent.Send); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(1, llm.calls); store.dispose()
    }
    @Test fun `save cancels active request and persists interrupted user turn`() = runTest(dispatcher) {
        val history = RecordingHistory(); val store = store(history, HangingLlm)
        store.accept(ChatIntent.ChangeDraft("draft")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals("draft", history.saved?.messages?.single()?.content); assertEquals(com.mypersonalassistent.core.history.api.DeliveryState.INTERRUPTED, history.saved?.messages?.single()?.deliveryState); store.dispose()
    }
    @Test fun `discard leaves loaded history untouched`() = runTest(dispatcher) {
        val persisted = ChatSnapshot("id", "title", 1, 1, listOf(com.mypersonalassistent.core.history.api.ChatMessage("m", com.mypersonalassistent.core.history.api.MessageRole.USER, "saved")))
        val history = RecordingHistory(persisted); val store = store(history, CountingLlm())
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(0, history.saveCalls); assertEquals(persisted.messages, store.state.messages); store.dispose()
    }
    @Test fun `failed save unlocks UI and keeps transcript`() = runTest(dispatcher) {
        val history = FailingSaveHistory(); val store = store(history, CountingLlm())
        store.accept(ChatIntent.ChangeDraft("retry")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals(1, history.saveCalls); assertFalse(store.state.saving); assertEquals(listOf("retry", "ok"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `retry after a failed send uses one current user turn`() = runTest(dispatcher) {
        val llm = FailThenSuccessLlm(); val store = store(NoHistory, llm)
        store.accept(ChatIntent.ChangeDraft("first")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.ChangeDraft("edited")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(listOf("edited"), llm.requests[1].messages.map { it.text })
        assertEquals(listOf("edited", "ok"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `discard after new changes preserves the prior saved snapshot`() = runTest(dispatcher) {
        val persisted = ChatSnapshot("id", "title", 1, 1, listOf(com.mypersonalassistent.core.history.api.ChatMessage("m", com.mypersonalassistent.core.history.api.MessageRole.USER, "saved")))
        val history = RecordingHistory(persisted); val store = store(history, CountingLlm())
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.ChangeDraft("new")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(0, history.saveCalls); assertEquals(persisted, history.read("id")); store.dispose()
    }
    @Test fun `discard cancels an active request without restoring its draft`() = runTest(dispatcher) {
        val llm = CancellableLlm(); val store = store(NoHistory, llm)
        store.accept(ChatIntent.ChangeDraft("draft")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(true, llm.cancelled); assertEquals("", store.state.draft); assertEquals(listOf("draft"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `save retry persists one unchanged snapshot after the first failure`() = runTest(dispatcher) {
        val history = FailsOnceHistory(); val store = store(history, CountingLlm())
        store.accept(ChatIntent.ChangeDraft("retry")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals(2, history.saveCalls); assertEquals(listOf("retry", "ok"), history.saved?.messages?.map { it.content }); store.dispose()
    }
    @Test fun `task context is applied and saved separately with chat`() = runTest(dispatcher) {
        val history = RecordingHistory(); val store = store(history, CountingLlm())
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.OpenTaskEditor)
        store.accept(ChatIntent.ChangeTaskGoal("Собрать память"))
        store.accept(ChatIntent.ChangeTaskConstraints("Без сети\nТолько локально"))
        store.accept(ChatIntent.ApplyTaskMemory)
        store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals("Собрать память", history.savedTask?.goal)
        assertEquals(listOf("Без сети", "Только локально"), history.savedTask?.constraints)
        store.dispose()
    }
    @Test fun `unsaved working task context affects the next request`() = runTest(dispatcher) {
        val composer = RecordingComposer()
        val store = store(NoHistory, CountingLlm(), composer)
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.OpenTaskEditor)
        store.accept(ChatIntent.ChangeTaskGoal("CURRENT_TASK_GOAL"))
        store.accept(ChatIntent.ApplyTaskMemory)
        store.accept(ChatIntent.ChangeDraft("question")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()
        assertEquals("CURRENT_TASK_GOAL", composer.task?.goal)
        store.dispose()
    }
    @Test fun `two loaded chats keep their task memory isolated`() = runTest(dispatcher) {
        val memory = MapMemory(mapOf(
            "first" to TaskMemory("first", goal = "FIRST_GOAL"),
            "second" to TaskMemory("second", goal = "SECOND_GOAL"),
        ))
        val first = store(NoHistory, CountingLlm(), id = "first", memory = memory)
        val second = store(NoHistory, CountingLlm(), id = "second", memory = memory)

        first.accept(ChatIntent.Load)
        second.accept(ChatIntent.Load)
        testScheduler.advanceUntilIdle()

        assertEquals("FIRST_GOAL", first.state.taskMemory.goal)
        assertEquals("SECOND_GOAL", second.state.taskMemory.goal)
        first.dispose()
        second.dispose()
    }
    @Test fun `cancelled task memory read does not become a chat storage error`() = runTest(dispatcher) {
        val memory = CancellingMemory()
        val store = store(NoHistory, CountingLlm(), memory = memory)
        store.accept(ChatIntent.Load)
        testScheduler.runCurrent()
        store.dispose()
        testScheduler.advanceUntilIdle()

        assertEquals(true, memory.cancelled)
    }
    private object FailingLlm : Llm { override suspend fun execute(request: LlmRequest) = LlmResult.Failure(LlmError.NETWORK) }
    private class CountingLlm : Llm { var calls = 0; override suspend fun execute(request: LlmRequest): LlmResult { calls++; return LlmResult.Success("ok", null) } }
    private object HangingLlm : Llm { override suspend fun execute(request: LlmRequest): LlmResult { awaitCancellation() } }
    private class FailThenSuccessLlm : Llm { val requests = mutableListOf<LlmRequest>(); override suspend fun execute(request: LlmRequest): LlmResult { requests += request; return if (requests.size == 1) LlmResult.Failure(LlmError.NETWORK) else LlmResult.Success("ok", null) } }
    private class CancellableLlm : Llm { var cancelled = false; override suspend fun execute(request: LlmRequest): LlmResult = try { awaitCancellation() } finally { cancelled = true } }
    private fun store(
        history: HistoryRepository,
        llm: Llm,
        composer: AgentRequestComposer = PassthroughComposer,
        id: String = "id",
        memory: MemoryRepository = EmptyMemory,
    ): ChatStore = ChatStoreFactory(
        DefaultStoreFactory(), id, history, memory, composer, llm,
    ).create()
    private object EmptyMemory : MemoryRepository {
        override suspend fun readProfile() = ProfileMemory()
        override suspend fun saveProfile(profile: ProfileMemory) = true
        override suspend fun skipProfile(updatedAt: Long) = true
        override suspend fun clearProfile(updatedAt: Long) = true
        override suspend fun readTaskMemory(chatId: String) = TaskMemory(chatId)
    }
    private class MapMemory(private val tasks: Map<String, TaskMemory>) : MemoryRepository by EmptyMemory {
        override suspend fun readTaskMemory(chatId: String) = tasks[chatId] ?: TaskMemory(chatId)
    }
    private class CancellingMemory : MemoryRepository by EmptyMemory {
        var cancelled = false
        override suspend fun readTaskMemory(chatId: String): TaskMemory = try {
            awaitCancellation()
        } finally {
            cancelled = true
        }
    }
    private object PassthroughComposer : AgentRequestComposer {
        override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?) = LlmRequest(
            messages.map { LlmMessage(if (it.role == com.mypersonalassistent.core.history.api.MessageRole.USER) LlmRole.USER else LlmRole.ASSISTANT, it.content) }
        )
    }
    private class RecordingComposer : AgentRequestComposer {
        var task: TaskMemory? = null
        override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?): LlmRequest {
            task = taskMemory
            return PassthroughComposer.compose(chatId, messages, taskMemory)
        }
    }
    private object NoHistory : HistoryRepository { override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot) = true; override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true }
    private class RecordingHistory(private val loaded: ChatSnapshot? = null) : HistoryRepository { var saved: ChatSnapshot? = null; var savedTask: TaskMemory? = null; var saveCalls = 0; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = loaded; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; saved = snapshot; return true }; override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean { savedTask = taskMemory; return save(snapshot) } }
    private class FailingSaveHistory : HistoryRepository { var saveCalls = 0; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; throw IllegalStateException("storage failure") }; override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean = save(snapshot) }
    private class FailsOnceHistory : HistoryRepository { var saveCalls = 0; var saved: ChatSnapshot? = null; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; if (saveCalls == 1) throw IllegalStateException("storage failure"); saved = snapshot; return true }; override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean = save(snapshot) }
}
