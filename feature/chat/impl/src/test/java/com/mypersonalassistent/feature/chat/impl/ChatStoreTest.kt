package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunResult
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.ResumeOperation
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.AgentRecoverySummary
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.chat.api.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class ChatStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `new task starts a workflow and appends only accepted final result`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Составь план")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()
        assertEquals(1, engine.starts)
        assertEquals(listOf("Составь план", "Готовый результат"), store.state.messages.map { it.content })
        assertEquals(AgentRunStatus.COMPLETED, store.state.checkpoint?.runStatus)
        store.dispose()
    }

    @Test fun `waiting question accepts one user answer instead of starting second run`() = runTest(dispatcher) {
        val engine = FakeEngine(questionFirst = true)
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(AgentRunStatus.WAITING_USER, store.state.checkpoint?.runStatus)
        store.accept(ChatIntent.ChangeDraft("Уточнение")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.starts); assertEquals(1, engine.answers)
        store.dispose()
    }

    @Test fun `start new task discards recovery locally and keeps transcript context`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.StartNewTask); testScheduler.advanceUntilIdle()
        assertTrue(engine.discarded.contains("id"))
        assertEquals(null, store.state.checkpoint)
        assertFalse(store.state.messages.isEmpty())
        store.dispose()
    }

    @Test fun `send after completed run starts a fresh workflow without a separate action`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Первая задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.ChangeDraft("Новая задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(2, engine.starts)
        store.dispose()
    }

    @Test fun `send is ignored for retryable and terminal failed runs until an explicit action`() = runTest(dispatcher) {
        val engine = FakeEngine(initialStatus = AgentRunStatus.FAILED, retryAllowed = true)
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Сломанная задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.ChangeDraft("Другая задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.starts)
        assertEquals("", store.state.draft)
        assertFalse(store.state.composerEditable)

        store.accept(ChatIntent.Retry); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.retries)
        store.dispose()
    }

    @Test fun `saved chat recovery requires explicit continue and loads without provider call`() = runTest(dispatcher) {
        val engine = FakeEngine(decodedCheckpoint = AgentCheckpoint(
            chatId = "id", runId = "recovered", phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.PAUSED, expectedAction = "Продолжить",
        ))
        val canonical = ChatSnapshot("id", "Сохранённый", 1, 2, listOf(ChatMessage("c", MessageRole.USER, "canonical")))
        val recovered = AgentRecovery(
            snapshot = ChatSnapshot("id", "Черновик", 1, 3, listOf(ChatMessage("r", MessageRole.USER, "recovery"))),
            taskMemory = TaskMemory("id", goal = "recovery goal"),
            checkpointJson = "recovery", isCanonicalChat = true, updatedAt = 3,
        )
        val store = store(engine = engine, recovery = FakeRecovery(recovered), history = FakeHistory(canonical))
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle()
        assertTrue(store.state.recoveryChoiceRequired)
        assertEquals(listOf("canonical"), store.state.messages.map { it.content })

        store.accept(ChatIntent.ContinueRecovery); testScheduler.advanceUntilIdle()
        assertFalse(store.state.recoveryChoiceRequired)
        assertEquals(listOf("recovery"), store.state.messages.map { it.content })
        assertEquals("recovered", store.state.checkpoint?.runId)
        assertEquals(0, engine.providerCalls)
        store.dispose()
    }

    @Test fun `discarding saved chat recovery restores its canonical snapshot`() = runTest(dispatcher) {
        val canonical = ChatSnapshot("id", "Сохранённый", 1, 2, listOf(ChatMessage("c", MessageRole.USER, "canonical")))
        val recovery = FakeRecovery(AgentRecovery(
            snapshot = ChatSnapshot("id", "Черновик", 1, 3, listOf(ChatMessage("r", MessageRole.USER, "recovery"))),
            taskMemory = TaskMemory("id"), checkpointJson = "recovery", isCanonicalChat = true, updatedAt = 3,
        ))
        val engine = FakeEngine(onDiscard = recovery::remove)
        val store = store(engine = engine, recovery = recovery, history = FakeHistory(canonical))
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle()
        assertTrue(store.state.recoveryChoiceRequired)

        store.accept(ChatIntent.DiscardRecovery); testScheduler.advanceUntilIdle()
        assertFalse(store.state.recoveryChoiceRequired)
        assertFalse(store.state.recoveryAvailable)
        assertEquals(listOf("canonical"), store.state.messages.map { it.content })
        store.dispose()
    }

    @Test fun `task memory editor is blocked for waiting paused and failed runs`() = runTest(dispatcher) {
        val waiting = store(engine = FakeEngine(questionFirst = true))
        waiting.accept(ChatIntent.ChangeDraft("Задача")); waiting.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        waiting.accept(ChatIntent.OpenTaskEditor)
        assertFalse(waiting.state.taskEditorOpen)
        waiting.accept(ChatIntent.Pause); testScheduler.advanceUntilIdle()
        waiting.accept(ChatIntent.OpenTaskEditor)
        assertFalse(waiting.state.taskEditorOpen)
        waiting.dispose()

        val failed = store(engine = FakeEngine(initialStatus = AgentRunStatus.FAILED))
        failed.accept(ChatIntent.ChangeDraft("Задача")); failed.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        failed.accept(ChatIntent.OpenTaskEditor)
        assertFalse(failed.state.taskEditorOpen)
        failed.dispose()
    }

    @Test fun `pause then resume waiting question restores it without another provider call`() = runTest(dispatcher) {
        val engine = FakeEngine(questionFirst = true)
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        val questionTranscript = store.state.messages.map { it.content }
        val callsBeforePause = engine.providerCalls

        store.accept(ChatIntent.Pause); testScheduler.advanceUntilIdle()
        assertEquals(AgentRunStatus.PAUSED, store.state.checkpoint?.runStatus)
        assertEquals(ResumeOperation.RESTORE_WAITING_USER, store.state.checkpoint?.resumeOperation)

        store.accept(ChatIntent.Resume); testScheduler.advanceUntilIdle()
        assertEquals(AgentRunStatus.WAITING_USER, store.state.checkpoint?.runStatus)
        assertEquals(questionTranscript, store.state.messages.map { it.content })
        assertEquals(callsBeforePause, engine.providerCalls)
        assertEquals(1, engine.resumes)
        store.dispose()
    }

    @Test fun `discard recovery cancels through engine and restores local state`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.DiscardRecovery); testScheduler.advanceUntilIdle()
        assertTrue(engine.discarded.contains("id"))
        store.dispose()
    }

    private fun store(
        engine: FakeEngine = FakeEngine(),
        recovery: FakeRecovery = FakeRecovery(),
        history: HistoryRepository = EmptyHistory,
    ): ChatStore = ChatStoreFactory(DefaultStoreFactory(), "id", history, EmptyMemory, engine, recovery).create()

    private class FakeEngine(
        private val questionFirst: Boolean = false,
        private val initialStatus: AgentRunStatus = AgentRunStatus.COMPLETED,
        private val retryAllowed: Boolean = false,
        private val decodedCheckpoint: AgentCheckpoint? = null,
        private val onDiscard: () -> Unit = {},
    ) : AgentRunEngine {
        private val checkpoints = MutableStateFlow<AgentCheckpoint?>(null)
        override val checkpoint: StateFlow<AgentCheckpoint?> = checkpoints
        var starts = 0; var answers = 0; var resumes = 0; var retries = 0; var providerCalls = 0; val discarded = mutableListOf<String>()
        private fun checkpoint(status: AgentRunStatus, expected: String = "Напишите новую задачу") = AgentCheckpoint("id", "run", phase = AgentPhase.DONE, runStatus = status, retryAllowed = retryAllowed, expectedAction = expected)
        override suspend fun start(input: AgentRunInput): AgentRunResult {
            starts++; providerCalls++
            val result = when {
                questionFirst -> AgentRunResult(checkpoint(AgentRunStatus.WAITING_USER, "Ответьте на вопрос"), visibleQuestion = "Уточните детали")
                initialStatus == AgentRunStatus.COMPLETED -> AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "Готовый результат")
                else -> AgentRunResult(checkpoint(initialStatus))
            }
            checkpoints.value = result.checkpoint
            return result
        }
        override suspend fun answer(input: AgentRunInput): AgentRunResult {
            answers++; providerCalls++
            return AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "Готовый результат").also { checkpoints.value = it.checkpoint }
        }
        override suspend fun resume(input: AgentRunInput): AgentRunResult {
            resumes++
            val checkpoint = requireNotNull(input.checkpoint)
            return if (checkpoint.resumeOperation == ResumeOperation.RESTORE_WAITING_USER) {
                AgentRunResult(checkpoint.copy(runStatus = AgentRunStatus.WAITING_USER, pausedFromStatus = null, resumeOperation = ResumeOperation.NONE, expectedAction = "Ответьте на вопрос"))
            } else {
                providerCalls++
                AgentRunResult(checkpoint)
            }.also { checkpoints.value = it.checkpoint }
        }
        override suspend fun retry(input: AgentRunInput): AgentRunResult {
            retries++
            return AgentRunResult(requireNotNull(input.checkpoint)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun pause(input: AgentRunInput): AgentCheckpoint {
            val checkpoint = checkpoints.value ?: requireNotNull(input.checkpoint)
            return checkpoint.copy(
                runStatus = AgentRunStatus.PAUSED,
                pausedFromStatus = checkpoint.runStatus,
                resumeOperation = if (checkpoint.runStatus == AgentRunStatus.WAITING_USER) ResumeOperation.RESTORE_WAITING_USER else ResumeOperation.REPEAT_CALL,
                expectedAction = "Продолжить",
            ).also { checkpoints.value = it }
        }
        override suspend fun persist(input: AgentRunInput) { checkpoints.value = input.checkpoint }
        override suspend fun discardRecovery(chatId: String): Boolean { discarded += chatId; checkpoints.value = null; onDiscard(); return true }
        override fun decodeCheckpoint(payload: String): AgentCheckpoint? = decodedCheckpoint
    }
    private class FakeRecovery(private val value: AgentRecovery? = null) : AgentRecoveryRepository {
        private var current = value
        val discarded = mutableListOf<String>()
        override fun observeRecovery(): Flow<List<AgentRecoverySummary>> = emptyFlow()
        override suspend fun readCheckpoint(chatId: String): String? = null
        override suspend fun readRecovery(chatId: String): AgentRecovery? = current
        override suspend fun writeRecovery(recovery: AgentRecovery) = true
        override suspend fun promoteRecovery(recovery: AgentRecovery) = true
        override suspend fun discardRecovery(chatId: String): Boolean { discarded += chatId; current = null; return true }
        fun remove() { current = null }
    }
    private object EmptyHistory : HistoryRepository {
        override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow()
        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot) = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true
    }
    private class FakeHistory(private val snapshot: ChatSnapshot) : HistoryRepository {
        override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow()
        override suspend fun read(id: String): ChatSnapshot? = snapshot
        override suspend fun save(snapshot: ChatSnapshot) = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true
    }
    private object EmptyMemory : MemoryRepository {
        override suspend fun readProfile() = ProfileMemory()
        override suspend fun saveProfile(profile: ProfileMemory) = true
        override suspend fun skipProfile(updatedAt: Long) = true
        override suspend fun clearProfile(updatedAt: Long) = true
        override suspend fun readTaskMemory(chatId: String) = TaskMemory(chatId)
    }
}
