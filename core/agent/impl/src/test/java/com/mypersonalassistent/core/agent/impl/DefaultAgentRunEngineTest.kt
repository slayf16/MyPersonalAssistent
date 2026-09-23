package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentFailureKind
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.AgentEvent
import com.mypersonalassistent.core.agent.api.MAX_EXTRA_ATTEMPTS_PER_RUN
import com.mypersonalassistent.core.agent.api.ResumeTarget
import com.mypersonalassistent.core.agent.api.AgentOperationToken
import com.mypersonalassistent.core.agent.api.PlanChangeContext
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.InvariantChange
import com.mypersonalassistent.core.invariants.api.GateOutcome
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantGuard
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.InvariantValidationError
import com.mypersonalassistent.core.invariants.api.PrepareMutationResult
import com.mypersonalassistent.core.invariants.api.ConfirmMutationResult
import com.mypersonalassistent.core.invariants.api.InvariantMutation
import com.mypersonalassistent.core.invariants.api.SnapshotResult
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentRunEngineTest {
    @Test fun `valid plan step and pass publish only validated final result`() = runBlocking {
        val llm = ScriptedLlm { request ->
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            when {
                command.contains("PLAN_READY") -> success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
                command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
                else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, clock = { 10L })

        val result = engine.start(input())

        assertEquals(3, llm.calls)
        assertEquals(AgentPhase.DONE, result.checkpoint.phase)
        assertEquals(AgentRunStatus.COMPLETED, result.checkpoint.runStatus)
        assertEquals("final candidate", result.finalResult)
        assertEquals(null, result.visibleQuestion)
        assertNotNull(store.recovery)
    }

    @Test fun `second planning question is terminal and never makes a third call`() = runBlocking {
        val llm = ScriptedLlm { request -> questionFor(request) }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        val first = engine.start(input())
        val second = engine.answer(input(first.checkpoint, listOf(message("task"), message("answer"))))

        assertEquals(2, llm.calls)
        assertEquals(AgentRunStatus.FAILED, second.checkpoint.runStatus)
        assertEquals(AgentFailureKind.WORKFLOW, second.failure)
        assertFalse(second.checkpoint.retryAllowed)
    }

    @Test fun `schema failure is retryable only within extra attempt budget`() = runBlocking {
        val llm = ScriptedLlm { request -> success("not json") }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        var result = engine.start(input())
        repeat(MAX_EXTRA_ATTEMPTS_PER_RUN) { result = engine.retry(input(result.checkpoint)) }

        assertEquals(3, llm.calls)
        assertEquals(AgentRunStatus.FAILED, result.checkpoint.runStatus)
        assertFalse(result.checkpoint.retryAllowed)
        assertEquals(AgentFailureKind.SCHEMA, result.failure)
    }

    @Test fun `recovery decoder rejects corrupt payload without leaking payload`() {
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm { error("unused") }, SimpleComposer, store, store)
        assertEquals(null, engine.decodeCheckpoint("{untrusted raw response}"))
    }

    @Test fun `pause then resume waiting user restores question without provider call`() = runBlocking {
        val llm = ScriptedLlm(::questionFor)
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        val waiting = engine.start(input())

        val paused = engine.pause(input(waiting.checkpoint)).checkpoint
        val resumed = engine.resume(input(paused))

        assertEquals(1, llm.calls)
        assertEquals(AgentRunStatus.PAUSED, paused.runStatus)
        assertEquals(ResumeTarget.RESTORE_WAITING_ANSWER, paused.resumeTarget)
        assertEquals(AgentRunStatus.WAITING_USER, resumed.checkpoint.runStatus)
        assertEquals(1, llm.calls)
    }

    @Test fun `local context rejection occurs before provider call and keeps provider budget`() = runBlocking {
        val llm = ScriptedLlm { error("provider must not run") }
        val store = RecoveryStore()
        val rejectingComposer = object : com.mypersonalassistent.core.agent.api.AgentRequestComposer {
            override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?) = LlmRequest(emptyList())
            override suspend fun composeForTask(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory, checkpointContext: String, phaseInstruction: String): LlmRequest =
                throw IllegalArgumentException("bounded context")
        }
        val result = DefaultAgentRunEngine(llm, rejectingComposer, store, store).start(input())

        assertEquals(0, llm.calls)
        assertEquals(AgentFailureKind.CONTEXT, result.failure)
        assertEquals(0, result.checkpoint.providerCallsUsed)
    }

    @Test fun `engine publishes each persisted provider checkpoint for progress`() = runBlocking {
        lateinit var engine: DefaultAgentRunEngine
        val observed = mutableListOf<AgentCheckpoint>()
        val llm = ScriptedLlm { request ->
            observed += requireNotNull(engine.checkpoint.value)
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            when {
                command.contains("PLAN_READY") -> success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
                command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
                else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
            }
        }
        val store = RecoveryStore()
        engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)

        engine.start(input())

        assertEquals(listOf(AgentPhase.PLANNING, AgentPhase.EXECUTION, AgentPhase.VALIDATION), observed.map { it.phase })
        assertEquals(listOf(1, 2, 3), observed.map { it.providerCallsUsed })
        assertEquals(AgentRunStatus.COMPLETED, engine.checkpoint.value?.runStatus)
    }

    @Test fun `pause after cancelled calls keeps the engine checkpoint identity and budgets`() = runBlocking {
        val llm = BlockingLlm()
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)

        val firstCall = async { engine.start(input()) }
        assertEquals(1, llm.started.receive())
        val firstPersisted = requireNotNull(engine.checkpoint.value)
        assertEquals(1, firstPersisted.providerCallsUsed)
        firstCall.cancelAndJoin()

        val firstPause = engine.pause(input()).checkpoint
        assertEquals(firstPersisted.runId, firstPause.runId)
        assertEquals(1, firstPause.providerCallsUsed)
        assertEquals(AgentRunStatus.PAUSED, firstPause.runStatus)

        val resumedCall = async { engine.resume(input(firstPause)) }
        assertEquals(2, llm.started.receive())
        val resumedPersisted = requireNotNull(engine.checkpoint.value)
        assertEquals(firstPause.runId, resumedPersisted.runId)
        assertEquals(2, resumedPersisted.providerCallsUsed)
        assertEquals(1, resumedPersisted.extraAttemptsUsed)
        resumedCall.cancelAndJoin()

        val secondPause = engine.pause(input()).checkpoint
        assertEquals(firstPause.runId, secondPause.runId)
        assertEquals(2, secondPause.providerCallsUsed)
        assertEquals(1, secondPause.extraAttemptsUsed)
        assertEquals(AgentRunStatus.PAUSED, secondPause.runStatus)
        assertEquals(secondPause, engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
    }

    @Test fun `persist registers durable nonterminal run against immutable snapshot`() = runBlocking {
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::questionFor), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())

        engine.start(input())

        assertEquals(1, repository.tracked.size)
        val tracked = repository.tracked.single()
        assertEquals("chat", tracked.chatId)
        assertEquals(repository.snapshot.ref(), tracked.snapshotRef)
        assertTrue(tracked.isNonterminal)
        assertTrue(tracked.isActive)
    }

    @Test fun `step and final gates refuse content before it becomes visible`() = runBlocking {
        val stepRepository = InvariantStore()
        val stepGuard = RecordingGuard(reject = InvariantGateStage.STEP)
        val stepEngine = DefaultAgentRunEngine(ScriptedLlm(::planStepPass), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = stepRepository, guard = stepGuard)
        val planned = stepEngine.start(input())
        val stepRejected = stepEngine.approvePlan(input(planned.checkpoint), planned.checkpoint.revision)

        assertEquals(AgentRunStatus.REFUSED, stepRejected.checkpoint.runStatus)
        assertTrue(InvariantGateStage.STEP in stepGuard.stages)
        assertEquals(null, stepRejected.finalResult)

        val finalRepository = InvariantStore()
        val finalGuard = RecordingGuard(reject = InvariantGateStage.FINAL)
        val finalEngine = DefaultAgentRunEngine(ScriptedLlm(::planStepPass), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = finalRepository, guard = finalGuard)
        val finalPlan = finalEngine.start(input())
        val finalRejected = finalEngine.approvePlan(input(finalPlan.checkpoint), finalPlan.checkpoint.revision)

        assertEquals(AgentRunStatus.REFUSED, finalRejected.checkpoint.runStatus)
        assertTrue(InvariantGateStage.FINAL in finalGuard.stages)
        assertEquals(null, finalRejected.finalResult)
    }

    @Test fun `checkpoint v2 round trips identity and changed policy replans from fresh snapshot`() = runBlocking {
        val store = RecoveryStore()
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, store, store, invariants = repository, guard = RecordingGuard())
        val checkpoint = AgentCheckpoint(
            chatId = "chat", runId = UUID.randomUUID().toString(), revision = 8,
            phase = AgentPhase.PLANNING, runStatus = AgentRunStatus.WAITING_USER,
            invariantSnapshot = repository.snapshot.ref(), approvedPlanRevision = 7, approvedAt = 6,
            staleTarget = ResumeTarget.REPEAT_EXECUTION_CALL, operationToken = AgentOperationToken(1),
        )
        engine.persist(input(checkpoint, listOf(message("task"), message("answer"))))
        val decoded = requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
        assertEquals(checkpoint.invariantSnapshot, decoded.invariantSnapshot)
        assertEquals(7L, decoded.approvedPlanRevision)
        assertEquals(6L, decoded.approvedAt)
        assertEquals(ResumeTarget.REPEAT_EXECUTION_CALL, decoded.staleTarget)
        assertEquals(AgentOperationToken(1), decoded.operationToken)

        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")
        val replanned = engine.answer(input(checkpoint, listOf(message("task"), message("answer"))))
        assertEquals(repository.snapshot.ref(), replanned.checkpoint.invariantSnapshot)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, replanned.checkpoint.runStatus)
    }

    @Test fun `provider event outside canonical state returns typed rejection`() = runBlocking {
        val llm = ScriptedLlm { request ->
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
        }
        val result = DefaultAgentRunEngine(llm, SimpleComposer, RecoveryStore(), RecoveryStore()).start(input())

        assertEquals(null, result.failure)
        assertEquals(AgentEvent.VALIDATION_PASS, result.rejectedTransition?.event)
        assertEquals(AgentRunStatus.ACTIVE, result.checkpoint.runStatus)
        assertEquals(1, result.checkpoint.providerCallsUsed)
    }

    @Test fun `plan change revalidates and clears stale validation operation state`() = runBlocking {
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())
        val stale = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), revision = 4, phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.WAITING_APPROVAL, invariantSnapshot = repository.snapshot.ref(),
            revisionPending = true, revisionIssues = listOf("old issue"), candidateResult = "old candidate", operationToken = AgentOperationToken(1),
        )
        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")

        val result = engine.requestPlanChanges(input(stale), PlanChangeContext(stale.revision, "change"))

        assertEquals(AgentRunStatus.WAITING_APPROVAL, result.checkpoint.runStatus)
        assertEquals(repository.snapshot.ref(), result.checkpoint.invariantSnapshot)
        assertFalse(result.checkpoint.revisionPending)
        assertTrue(result.checkpoint.revisionIssues.isEmpty())
        assertEquals("", result.checkpoint.candidateResult)
        assertTrue(result.checkpoint.operationToken.value >= 1)
    }

    @Test fun `index write failure is fail closed before recovery and provider`() = runBlocking {
        val repository = InvariantStore(trackSucceeds = false)
        val recovery = RecoveryStore()
        val llm = ScriptedLlm { error("provider must not execute") }
        val result = DefaultAgentRunEngine(llm, SimpleComposer, recovery, recovery, invariants = repository, guard = RecordingGuard()).start(input())

        assertEquals(0, llm.calls)
        assertEquals(null, recovery.recovery)
        assertEquals(AgentFailureKind.PROVIDER, result.failure)
    }

    @Test fun `continue current rules validates snapshot and clears obsolete revision operation`() = runBlocking {
        val repository = InvariantStore()
        val stale = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), revision = 3, phase = AgentPhase.VALIDATION,
            runStatus = AgentRunStatus.STALE_PAUSED, invariantSnapshot = repository.snapshot.ref(),
            revisionPending = true, revisionIssues = listOf("obsolete"), candidateResult = "obsolete candidate", operationToken = AgentOperationToken(1),
        )
        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")
        val result = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())
            .continueWithCurrentRules(input(stale))

        assertEquals(AgentRunStatus.WAITING_APPROVAL, result.checkpoint.runStatus)
        assertFalse(result.checkpoint.revisionPending)
        assertTrue(result.checkpoint.revisionIssues.isEmpty())
        assertEquals("", result.checkpoint.candidateResult)
        assertTrue(result.checkpoint.operationToken.value >= 1)
        assertEquals(repository.snapshot.ref(), result.checkpoint.invariantSnapshot)
    }

    @Test fun `rejected second start leaves existing checkpoint and storage untouched`() = runBlocking {
        val repository = InvariantStore()
        val recovery = RecoveryStore()
        val llm = ScriptedLlm(::questionFor)
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, recovery, recovery, invariants = repository, guard = RecordingGuard())
        val first = engine.start(input())
        val persisted = recovery.recovery
        val second = engine.start(input())

        assertEquals(1, llm.calls)
        assertEquals(first.checkpoint, second.checkpoint)
        assertEquals(AgentEvent.START, second.rejectedTransition?.event)
        assertEquals(persisted, recovery.recovery)
    }

    private fun input(checkpoint: AgentCheckpoint? = null, messages: List<ChatMessage> = listOf(message("task"))) =
        AgentRunInput("chat", messages, TaskMemory("chat", goal = "goal"), checkpoint)
    private fun message(text: String) = ChatMessage(text, MessageRole.USER, text)
    private fun success(text: String) = LlmResult.Success(text, null)
    private fun questionFor(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"NEEDS_USER","runId":"$runId","revision":$revision,"question":"Need detail","expectedInput":"A short detail"}""")
    }
    private fun planReady(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
    }
    private fun planStepPass(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return when {
            command.contains("PLAN_READY") -> planReady(request)
            command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
            else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
        }
    }

    private class ScriptedLlm(private val script: (LlmRequest) -> LlmResult) : Llm {
        var calls = 0
        override suspend fun execute(request: LlmRequest): LlmResult { calls++; return script(request) }
    }
    private class BlockingLlm : Llm {
        val started = Channel<Int>(Channel.UNLIMITED)
        private var calls = 0
        override suspend fun execute(request: LlmRequest): LlmResult {
            started.send(++calls)
            awaitCancellation()
        }
    }
    private object SimpleComposer : com.mypersonalassistent.core.agent.api.AgentRequestComposer {
        override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?) = LlmRequest(emptyList())
        override suspend fun composeForTask(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory, checkpointContext: String, phaseInstruction: String) =
            LlmRequest(listOf(com.mypersonalassistent.core.llm.api.LlmMessage(com.mypersonalassistent.core.llm.api.LlmRole.SYSTEM, phaseInstruction)))
    }
    private class RecoveryStore : HistoryRepository, AgentRecoveryRepository {
        var recovery: AgentRecovery? = null
        override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow()
        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot) = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true
        override fun observeRecovery(): Flow<List<com.mypersonalassistent.core.history.api.AgentRecoverySummary>> = emptyFlow()
        override suspend fun readCheckpoint(chatId: String): String? = null
        override suspend fun readRecovery(chatId: String) = recovery
        override suspend fun writeRecovery(recovery: AgentRecovery): Boolean { this.recovery = recovery; return true }
        override suspend fun promoteRecovery(recovery: AgentRecovery) = true
        override suspend fun discardRecovery(chatId: String): Boolean { recovery = null; return true }
    }
    private data class TrackedRun(val chatId: String, val runId: String, val snapshotRef: InvariantSnapshotRef, val isNonterminal: Boolean, val isActive: Boolean)
    private class InvariantStore(private val trackSucceeds: Boolean = true) : InvariantRepository {
        var revision = CollectionRevision(1)
        var snapshot = InvariantSnapshot(InvariantSnapshotId("snapshot-1"), revision, emptyList(), "digest-1", 1)
        val tracked = mutableListOf<TrackedRun>()
        override val rules: Flow<List<InvariantRule>> = emptyFlow()
        override val changes: Flow<InvariantChange> = emptyFlow()
        override suspend fun collectionRevision() = revision
        override suspend fun read(id: InvariantRuleId): InvariantRule? = null
        override suspend fun prepareMutation(mutation: InvariantMutation): PrepareMutationResult = PrepareMutationResult.Rejected(InvariantValidationError.StorageUnavailable)
        override suspend fun confirmMutation(confirmationId: String): ConfirmMutationResult = ConfirmMutationResult.Rejected(InvariantValidationError.StorageUnavailable)
        override suspend fun createSnapshot(): SnapshotResult = SnapshotResult.Available(snapshot)
        override suspend fun readSnapshot(ref: InvariantSnapshotRef): SnapshotResult = if (ref.id == snapshot.id || ref.id.value == "snapshot-1") SnapshotResult.Available(if (ref.id == snapshot.id) snapshot else snapshot.copy(id = ref.id, collectionRevision = ref.collectionRevision, contentDigest = ref.contentDigest)) else SnapshotResult.Unavailable
        override suspend fun trackRun(chatId: String, runId: String, snapshotRef: InvariantSnapshotRef, isNonterminal: Boolean, isActive: Boolean, isStale: Boolean, staleTarget: String?): Boolean { if (trackSucceeds) tracked += TrackedRun(chatId, runId, snapshotRef, isNonterminal, isActive); return trackSucceeds }
    }
    private class RecordingGuard(private val reject: InvariantGateStage? = null) : InvariantGuard {
        val stages = mutableListOf<InvariantGateStage>()
        override suspend fun check(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): GateOutcome {
            stages += stage
            return if (stage == reject) GateOutcome.Rejected(SafeInvariantRefusal(null, null, null, "safe")) else GateOutcome.Allowed(snapshot.ref(), sha256(artifact))
        }
        private fun InvariantSnapshot.ref() = InvariantSnapshotRef(id, collectionRevision, contentDigest)
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun InvariantSnapshot.ref() = InvariantSnapshotRef(id, collectionRevision, contentDigest)
}
