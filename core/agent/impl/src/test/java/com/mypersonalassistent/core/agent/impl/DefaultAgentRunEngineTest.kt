package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentFailureKind
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.MAX_EXTRA_ATTEMPTS_PER_RUN
import com.mypersonalassistent.core.agent.api.ResumeOperation
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

        val paused = engine.pause(input(waiting.checkpoint))
        val resumed = engine.resume(input(paused))

        assertEquals(1, llm.calls)
        assertEquals(AgentRunStatus.PAUSED, paused.runStatus)
        assertEquals(ResumeOperation.RESTORE_WAITING_USER, paused.resumeOperation)
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

        val firstPause = engine.pause(input())
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

        val secondPause = engine.pause(input())
        assertEquals(firstPause.runId, secondPause.runId)
        assertEquals(2, secondPause.providerCallsUsed)
        assertEquals(1, secondPause.extraAttemptsUsed)
        assertEquals(AgentRunStatus.PAUSED, secondPause.runStatus)
        assertEquals(secondPause, engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
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
}
