package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunResult
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.StartNewTask
import com.mypersonalassistent.core.agent.api.PlanChangeContext
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
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.mcp.api.ChatMcpRepository
import com.mypersonalassistent.core.mcp.api.McpPermission
import com.mypersonalassistent.core.mcp.api.McpResult
import com.mypersonalassistent.core.mcp.api.McpOperationCoordinator
import com.mypersonalassistent.core.agent.api.PendingMcpCall
import com.mypersonalassistent.core.agent.api.McpCallStatus
import com.mypersonalassistent.feature.chat.api.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
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

    @Test fun `start new task projects authoritative engine result without a local recovery clear`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.StartNewTask); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.newTasks)
        assertEquals(AgentRunStatus.TERMINATED, store.state.checkpoint?.runStatus)
        assertTrue(engine.discarded.isEmpty())
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
            runStatus = AgentRunStatus.ACTIVE, expectedAction = "Продолжить",
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

    @Test fun `task memory editor is blocked for waiting and failed runs`() = runTest(dispatcher) {
        val waiting = store(engine = FakeEngine(questionFirst = true))
        waiting.accept(ChatIntent.ChangeDraft("Задача")); waiting.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        waiting.accept(ChatIntent.OpenTaskEditor)
        assertFalse(waiting.state.taskEditorOpen)
        waiting.dispose()

        val failed = store(engine = FakeEngine(initialStatus = AgentRunStatus.FAILED))
        failed.accept(ChatIntent.ChangeDraft("Задача")); failed.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        failed.accept(ChatIntent.OpenTaskEditor)
        assertFalse(failed.state.taskEditorOpen)
        failed.dispose()
    }

    @Test fun `discard recovery cancels through engine and restores local state`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val store = store(engine = engine)
        store.accept(ChatIntent.DiscardRecovery); testScheduler.advanceUntilIdle()
        assertTrue(engine.discarded.contains("id"))
        store.dispose()
    }

    @Test fun `approval is explicit and request changes rejects blank comment locally`() = runTest(dispatcher) {
        val engine = FakeEngine(initialStatus = AgentRunStatus.WAITING_APPROVAL)
        val store = store(engine = engine)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(AgentRunStatus.WAITING_APPROVAL, store.state.checkpoint?.runStatus)
        assertEquals(0, engine.approvals)

        store.accept(ChatIntent.OpenPlanChanges)
        store.accept(ChatIntent.SubmitPlanChanges); testScheduler.advanceUntilIdle()
        assertEquals(0, engine.planChanges)

        store.accept(ChatIntent.ChangePlanComment("Добавить проверку Unicode ✨"))
        store.accept(ChatIntent.SubmitPlanChanges); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.planChanges)
        assertEquals("Добавить проверку Unicode ✨", engine.lastPlanChange?.comment)
        assertEquals(store.state.checkpoint?.revision, engine.lastPlanChange?.basePlanRevision)

        store.accept(ChatIntent.ApprovePlan); testScheduler.advanceUntilIdle()
        assertEquals(1, engine.approvals)
        assertEquals(AgentRunStatus.COMPLETED, store.state.checkpoint?.runStatus)
        store.dispose()
    }

    @Test fun `disabling MCP cancels suspended call drops its late result and continues without MCP`() = runTest(dispatcher) {
        val engine = FakeEngine(
            initialStatus = AgentRunStatus.WAITING_MCP_APPROVAL,
            blockMcpDecision = true,
        )
        val mcp = RecordingMcp()
        val store = store(engine = engine, mcp = mcp)

        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, store.state.checkpoint?.runStatus)

        store.accept(ChatIntent.DecideMcpCall("digest", allow = true))
        testScheduler.runCurrent()
        assertEquals(1, engine.mcpDecisions)

        store.accept(ChatIntent.SetMcpPermission("server", enabled = false))
        testScheduler.advanceUntilIdle()

        assertTrue(engine.lateMcpDecisionDelivered)
        assertEquals(1, engine.interruptedMcp)
        assertEquals(listOf("server" to false), mcp.changes)
        assertTrue(store.state.mcpSuppressed)
        assertEquals(AgentRunStatus.ACTIVE, store.state.checkpoint?.runStatus)
        assertFalse(store.state.messages.any { it.content == "late MCP result" })

        store.accept(ChatIntent.ChangeDraft("Продолжи без инструмента")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()

        assertEquals(1, engine.answers)
        assertTrue(engine.answerSawMcpSuppressed)
        assertEquals(AgentRunStatus.COMPLETED, store.state.checkpoint?.runStatus)
        assertFalse(store.state.messages.any { it.content == "late MCP result" })
        store.dispose()
    }

    @Test fun `catalog deletion cancels registered call and persists unknown normalization`() = runTest(dispatcher) {
        val engine = FakeEngine(initialStatus = AgentRunStatus.WAITING_MCP_APPROVAL, blockMcpDecision = true)
        val operations = RecordingOperations()
        val store = store(engine = engine, operations = operations)
        store.accept(ChatIntent.ChangeDraft("Задача")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.DecideMcpCall("digest", allow = true))
        testScheduler.runCurrent()

        operations.cancelAndJoin("server")
        testScheduler.advanceUntilIdle()

        assertTrue(engine.lateMcpDecisionDelivered)
        assertEquals(1, engine.interruptedMcp)
        assertTrue(store.state.mcpSuppressed)
        assertEquals(AgentRunStatus.ACTIVE, store.state.checkpoint?.runStatus)
        assertFalse(store.state.messages.any { it.content == "late MCP result" })
        store.dispose()
    }

    @Test fun `terminal refusal projects exact safe metadata without a statement`() = runTest(dispatcher) {
        val safe = SafeInvariantRefusal(
            title = "Не отправлять секреты",
            category = InvariantCategory.BUSINESS_RULE,
            ruleId = InvariantRuleId("rule-safe-42"),
            explanation = "Запрошенное действие конфликтует с правилом.",
        )
        val store = store(engine = FakeEngine(refusalOnStart = safe))

        store.accept(ChatIntent.ChangeDraft("Сделай запрос")); store.accept(ChatIntent.Send)
        testScheduler.advanceUntilIdle()

        assertEquals(AgentRunStatus.REFUSED, store.state.checkpoint?.runStatus)
        assertEquals(safe, store.state.safeRefusal)
        assertEquals(
            "Не могу продолжить: результат противоречит обязательному правилу “Не отправлять секреты” (BUSINESS_RULE, rule-safe-42). Запрошенное действие конфликтует с правилом.",
            store.state.agentUi.expectedAction,
        )
        assertFalse(store.state.agentUi.expectedAction.contains("секретный текст правила"))
        store.dispose()
    }

    private fun store(
        engine: FakeEngine = FakeEngine(),
        recovery: FakeRecovery = FakeRecovery(),
        history: HistoryRepository = EmptyHistory,
        mcp: ChatMcpRepository = EmptyMcp,
        operations: McpOperationCoordinator = EmptyOperations,
    ): ChatStore = ChatStoreFactory(DefaultStoreFactory(), "id", history, EmptyMemory, engine, recovery, mcp = mcp, operations = operations).create()

    private class FakeEngine(
        private val questionFirst: Boolean = false,
        private val initialStatus: AgentRunStatus = AgentRunStatus.COMPLETED,
        private val retryAllowed: Boolean = false,
        private val decodedCheckpoint: AgentCheckpoint? = null,
        private val onDiscard: () -> Unit = {},
        private val refusalOnStart: SafeInvariantRefusal? = null,
        private val blockMcpDecision: Boolean = false,
    ) : AgentRunEngine {
        private val checkpoints = MutableStateFlow<AgentCheckpoint?>(null)
        override val checkpoint: StateFlow<AgentCheckpoint?> = checkpoints
        var starts = 0; var answers = 0; var retries = 0; var approvals = 0; var planChanges = 0; var continuedWithCurrentRules = 0; var providerCalls = 0; var interruptedForRecovery = 0; var mcpDecisions = 0; var interruptedMcp = 0; var lateMcpDecisionDelivered = false; var answerSawMcpSuppressed = false; val discarded = mutableListOf<String>()
        var lastPlanChange: PlanChangeContext? = null
        private fun checkpoint(status: AgentRunStatus, expected: String = "Напишите новую задачу") = AgentCheckpoint(
            "id", "run", phase = AgentPhase.DONE, runStatus = status, retryAllowed = retryAllowed, expectedAction = expected,
            pendingMcpCalls = if (status == AgentRunStatus.WAITING_MCP_APPROVAL) listOf(
                PendingMcpCall("server", "lookup", "call", "{}", "digest", McpCallStatus.WAITING_CONFIRMATION),
            ) else emptyList(),
        )
        override suspend fun start(input: AgentRunInput): AgentRunResult {
            starts++; providerCalls++
            val result = when {
                refusalOnStart != null -> AgentRunResult(
                    checkpoint(AgentRunStatus.REFUSED).copy(refusal = refusalOnStart),
                    refusal = refusalOnStart,
                )
                questionFirst -> AgentRunResult(checkpoint(AgentRunStatus.WAITING_USER, "Ответьте на вопрос"), visibleQuestion = "Уточните детали")
                initialStatus == AgentRunStatus.COMPLETED -> AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "Готовый результат")
                else -> AgentRunResult(checkpoint(initialStatus))
            }
            checkpoints.value = result.checkpoint
            return result
        }
        override suspend fun answer(input: AgentRunInput): AgentRunResult {
            answers++; providerCalls++; answerSawMcpSuppressed = input.checkpoint?.mcpSuppressed == true
            return AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "Готовый результат").also { checkpoints.value = it.checkpoint }
        }
        override suspend fun decideMcpCall(input: AgentRunInput, digest: String, allow: Boolean): AgentRunResult {
            mcpDecisions++
            if (blockMcpDecision) {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    lateMcpDecisionDelivered = true
                    return AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "late MCP result")
                }
            }
            return AgentRunResult(checkpoint(AgentRunStatus.COMPLETED)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun interruptMcp(input: AgentRunInput): AgentRunResult {
            interruptedMcp++
            val normalized = requireNotNull(input.checkpoint).copy(
                runStatus = AgentRunStatus.ACTIVE,
                mcpSuppressed = true,
                inFlight = false,
                expectedAction = "Продолжайте без MCP",
            )
            return AgentRunResult(normalized).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun retry(input: AgentRunInput): AgentRunResult {
            retries++
            return AgentRunResult(requireNotNull(input.checkpoint)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun approvePlan(input: AgentRunInput, expectedPlanRevision: Long): AgentRunResult {
            approvals++
            providerCalls++
            return AgentRunResult(checkpoint(AgentRunStatus.COMPLETED), finalResult = "Готовый результат").also { checkpoints.value = it.checkpoint }
        }
        override suspend fun requestPlanChanges(input: AgentRunInput, context: PlanChangeContext): AgentRunResult {
            planChanges++
            lastPlanChange = context
            return AgentRunResult(requireNotNull(input.checkpoint)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun continueWithCurrentRules(input: AgentRunInput): AgentRunResult {
            continuedWithCurrentRules++
            return AgentRunResult(requireNotNull(input.checkpoint)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun normalizeInterruptedForRecovery(input: AgentRunInput): AgentRunResult {
            interruptedForRecovery++
            return AgentRunResult(requireNotNull(input.checkpoint).copy(inFlight = false)).also { checkpoints.value = it.checkpoint }
        }
        override suspend fun persist(input: AgentRunInput) { checkpoints.value = input.checkpoint }
        var newTasks = 0
        override suspend fun startNewTask(command: StartNewTask): AgentRunResult {
            newTasks++
            val current = checkpoints.value ?: checkpoint(AgentRunStatus.TERMINATED)
            return AgentRunResult(current.copy(runStatus = AgentRunStatus.TERMINATED, expectedAction = "Напишите новую задачу", refusal = null)).also { checkpoints.value = it.checkpoint }
        }
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
    private object EmptyMcp : ChatMcpRepository {
        override suspend fun permissions(chatId: String): List<McpPermission> = emptyList()
        override suspend fun setEnabled(chatId: String, serverId: String, enabled: Boolean): McpResult<Unit> = McpResult.Success(Unit)
    }
    private class RecordingMcp : ChatMcpRepository {
        val changes = mutableListOf<Pair<String, Boolean>>()
        override suspend fun permissions(chatId: String): List<McpPermission> = emptyList()
        override suspend fun setEnabled(chatId: String, serverId: String, enabled: Boolean): McpResult<Unit> {
            changes += serverId to enabled
            return McpResult.Success(Unit)
        }
    }
    private object EmptyOperations : McpOperationCoordinator {
        override fun register(serverId: String, job: Job, invalidateBeforeCancellation: () -> Unit, normalizeAfterCancellation: suspend () -> Unit) = Unit
        override fun unregister(serverId: String, job: Job) = Unit
        override suspend fun cancelAndJoin(serverId: String) = Unit
    }
    private class RecordingOperations : McpOperationCoordinator {
        private data class Registered(val serverId: String, val job: Job, val invalidate: () -> Unit, val normalize: suspend () -> Unit)
        private var registered: Registered? = null
        override fun register(serverId: String, job: Job, invalidateBeforeCancellation: () -> Unit, normalizeAfterCancellation: suspend () -> Unit) { registered = Registered(serverId, job, invalidateBeforeCancellation, normalizeAfterCancellation) }
        override fun unregister(serverId: String, job: Job) { if (registered?.job == job) registered = null }
        override suspend fun cancelAndJoin(serverId: String) {
            val active = registered?.takeIf { it.serverId == serverId } ?: return
            registered = null
            active.invalidate.invoke()
            active.job.cancel()
            active.normalize.invoke()
            active.job.join()
        }
    }
}
