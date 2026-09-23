package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentFailureKind
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentPlanStep
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunResult
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.MAX_EXTRA_ATTEMPTS_PER_RUN
import com.mypersonalassistent.core.agent.api.MAX_PROVIDER_CALLS_PER_RUN
import com.mypersonalassistent.core.agent.api.ResumeOperation
import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class DefaultAgentRunEngine(
    private val llm: Llm,
    private val composer: AgentRequestComposer,
    private val history: HistoryRepository,
    private val recovery: AgentRecoveryRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = false },
) : AgentRunEngine {
    private val mutableCheckpoint = MutableStateFlow<AgentCheckpoint?>(null)
    override val checkpoint: StateFlow<AgentCheckpoint?> = mutableCheckpoint.asStateFlow()

    override suspend fun start(input: AgentRunInput): AgentRunResult {
        val checkpoint = AgentCheckpoint(
            chatId = input.chatId,
            runId = UUID.randomUUID().toString(),
            phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.ACTIVE,
            expectedAction = WAIT,
            updatedAt = clock(),
        )
        return advance(input.copy(checkpoint = checkpoint), checkpoint)
    }

    override suspend fun answer(input: AgentRunInput): AgentRunResult {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        if (checkpoint.phase != AgentPhase.PLANNING || checkpoint.runStatus != AgentRunStatus.WAITING_USER) return invalid(input)
        return advance(input, checkpoint.copy(runStatus = AgentRunStatus.ACTIVE, expectedAction = WAIT, revision = checkpoint.revision + 1, updatedAt = clock()))
    }

    override suspend fun resume(input: AgentRunInput): AgentRunResult {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        if (checkpoint.runStatus != AgentRunStatus.PAUSED) return invalid(input)
        if (checkpoint.resumeOperation == ResumeOperation.RESTORE_WAITING_USER) {
            return AgentRunResult(checkpoint.copy(runStatus = AgentRunStatus.WAITING_USER, pausedFromStatus = null, resumeOperation = ResumeOperation.NONE, expectedAction = ANSWER, updatedAt = clock()))
        }
        if (!canSpendExtra(checkpoint)) return terminalBudget(checkpoint)
        return advance(input, checkpoint.copy(
            runStatus = AgentRunStatus.ACTIVE,
            extraAttemptsUsed = checkpoint.extraAttemptsUsed + 1,
            pausedFromStatus = null,
            resumeOperation = ResumeOperation.NONE,
            expectedAction = WAIT,
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        ))
    }

    override suspend fun retry(input: AgentRunInput): AgentRunResult {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        if (checkpoint.runStatus != AgentRunStatus.FAILED || !checkpoint.retryAllowed) return invalid(input)
        if (!canSpendExtra(checkpoint)) return terminalBudget(checkpoint)
        return advance(input, checkpoint.copy(
            runStatus = AgentRunStatus.ACTIVE,
            retryAllowed = false,
            failureKind = null,
            extraAttemptsUsed = checkpoint.extraAttemptsUsed + 1,
            expectedAction = WAIT,
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        ))
    }

    override suspend fun pause(input: AgentRunInput): AgentCheckpoint {
        val checkpoint = currentCheckpoint(input) ?: return requireNotNull(input.checkpoint)
        if (checkpoint.runStatus != AgentRunStatus.ACTIVE && checkpoint.runStatus != AgentRunStatus.WAITING_USER) return checkpoint
        val paused = checkpoint.copy(
            runStatus = AgentRunStatus.PAUSED,
            pausedFromStatus = checkpoint.runStatus,
            resumeOperation = if (checkpoint.runStatus == AgentRunStatus.WAITING_USER) ResumeOperation.RESTORE_WAITING_USER else ResumeOperation.REPEAT_CALL,
            inFlight = false,
            expectedAction = RESUME,
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        )
        persist(input.copy(checkpoint = paused))
        return paused
    }

    override suspend fun persist(input: AgentRunInput) {
        val checkpoint = requireNotNull(input.checkpoint)
        validateRecoveryInput(input)
        val now = clock()
        val snapshot = ChatSnapshot(
            id = input.chatId,
            title = titleOf(input.messages),
            createdAt = input.messages.firstOrNull()?.let { now } ?: now,
            updatedAt = now,
            messages = input.messages,
        )
        val isCanonical = history.read(input.chatId) != null
        if (!recovery.writeRecovery(AgentRecovery(snapshot, input.taskMemory, encode(checkpoint), isCanonical, now))) {
            throw IllegalStateException("Recovery write failed")
        }
        publish(checkpoint)
    }

    override suspend fun discardRecovery(chatId: String): Boolean = recovery.discardRecovery(chatId)

    override fun decodeCheckpoint(payload: String): AgentCheckpoint? = try {
        decode(payload)
    } catch (_: Throwable) {
        null
    }

    private suspend fun advance(input: AgentRunInput, initial: AgentCheckpoint): AgentRunResult {
        var checkpoint = initial
        while (checkpoint.runStatus == AgentRunStatus.ACTIVE) {
            val request = try {
                composer.composeForTask(
                    input.chatId,
                    input.messages,
                    input.taskMemory,
                    checkpointContext(checkpoint),
                    phaseInstruction(checkpoint),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                return failure(checkpoint, AgentFailureKind.CONTEXT).also { publish(it.checkpoint) }
            } catch (_: Throwable) {
                return failure(checkpoint, AgentFailureKind.CONTEXT).also { publish(it.checkpoint) }
            }
            val prepared = prepareProviderCall(checkpoint)
            if (prepared == null) return terminalBudget(checkpoint).also { publish(it.checkpoint) }
            checkpoint = prepared
            try {
                persist(input.copy(checkpoint = checkpoint))
                when (val provider = llm.execute(request)) {
                    is LlmResult.Failure -> return failure(checkpoint, AgentFailureKind.PROVIDER).also { publish(it.checkpoint) }
                    is LlmResult.Success -> {
                        when (val event = AgentEnvelopeParser.parse(provider.text, checkpoint)) {
                            is EnvelopeResult.Failure -> return failure(checkpoint, AgentFailureKind.SCHEMA).also { publish(it.checkpoint) }
                            is EnvelopeResult.Plan -> {
                                if (checkpoint.planningCallsUsed > 2) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                checkpoint = checkpoint.copy(
                                    phase = AgentPhase.EXECUTION,
                                    plan = event.steps,
                                    currentStepIndex = 0,
                                    inFlight = false,
                                    expectedAction = WAIT,
                                    revision = checkpoint.revision + 1,
                                    updatedAt = clock(),
                                )
                            }
                            is EnvelopeResult.Question -> {
                                return if (checkpoint.planningCallsUsed == 1) {
                                    AgentRunResult(checkpoint.copy(
                                        runStatus = AgentRunStatus.WAITING_USER,
                                        inFlight = false,
                                        expectedAction = ANSWER,
                                        revision = checkpoint.revision + 1,
                                        updatedAt = clock(),
                                    ), visibleQuestion = event.question).also { publish(it.checkpoint) }
                                } else terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                            }
                            is EnvelopeResult.Step -> {
                                if (checkpoint.phase != AgentPhase.EXECUTION) return failure(checkpoint, AgentFailureKind.SCHEMA).also { publish(it.checkpoint) }
                                checkpoint = if (checkpoint.revisionPending) {
                                    checkpoint.copy(
                                        phase = AgentPhase.VALIDATION,
                                        candidateResult = event.artifact,
                                        revisionPending = false,
                                        inFlight = false,
                                        expectedAction = WAIT,
                                        revision = checkpoint.revision + 1,
                                        updatedAt = clock(),
                                    )
                                } else {
                                    val summaries = checkpoint.acceptedSummaries + event.summary
                                    val last = checkpoint.currentStepIndex == checkpoint.plan.lastIndex
                                    checkpoint.copy(
                                        phase = if (last) AgentPhase.VALIDATION else AgentPhase.EXECUTION,
                                        currentStepIndex = if (last) checkpoint.currentStepIndex else checkpoint.currentStepIndex + 1,
                                        acceptedSummaries = summaries,
                                        candidateResult = if (last) event.artifact else checkpoint.candidateResult,
                                        inFlight = false,
                                        expectedAction = WAIT,
                                        revision = checkpoint.revision + 1,
                                        updatedAt = clock(),
                                    )
                                }
                            }
                            EnvelopeResult.Pass -> {
                                if (checkpoint.phase != AgentPhase.VALIDATION) return failure(checkpoint, AgentFailureKind.SCHEMA).also { publish(it.checkpoint) }
                                return AgentRunResult(checkpoint.copy(
                                    phase = AgentPhase.DONE,
                                    runStatus = AgentRunStatus.COMPLETED,
                                    inFlight = false,
                                    expectedAction = NEW_TASK,
                                    revision = checkpoint.revision + 1,
                                    updatedAt = clock(),
                                ), finalResult = checkpoint.candidateResult).also { publish(it.checkpoint) }
                            }
                            is EnvelopeResult.Revise -> {
                                if (checkpoint.phase != AgentPhase.VALIDATION) return failure(checkpoint, AgentFailureKind.SCHEMA).also { publish(it.checkpoint) }
                                if (checkpoint.validationAttempts >= 2) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                checkpoint = checkpoint.copy(
                                    phase = AgentPhase.EXECUTION,
                                    revisionPending = true,
                                    revisionIssues = event.issues,
                                    inFlight = false,
                                    expectedAction = WAIT,
                                    revision = checkpoint.revision + 1,
                                    updatedAt = clock(),
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return failure(checkpoint, AgentFailureKind.PROVIDER).also { publish(it.checkpoint) }
            }
        }
        return AgentRunResult(checkpoint).also { publish(it.checkpoint) }
    }

    private fun currentCheckpoint(input: AgentRunInput): AgentCheckpoint? {
        val requested = input.checkpoint
        return checkpoint.value?.takeIf { current ->
            current.chatId == input.chatId && (requested == null || current.runId == requested.runId)
        } ?: requested
    }

    private fun publish(next: AgentCheckpoint) {
        val previous = mutableCheckpoint.value
        if (previous == null || previous.chatId != next.chatId || previous.runId != next.runId || next.revision >= previous.revision) {
            mutableCheckpoint.value = next
        }
    }

    private fun prepareProviderCall(checkpoint: AgentCheckpoint): AgentCheckpoint? {
        if (checkpoint.providerCallsUsed >= MAX_PROVIDER_CALLS_PER_RUN) return null
        return when (checkpoint.phase) {
            AgentPhase.PLANNING -> checkpoint.copy(providerCallsUsed = checkpoint.providerCallsUsed + 1, planningCallsUsed = checkpoint.planningCallsUsed + 1, inFlight = true, updatedAt = clock())
            AgentPhase.EXECUTION -> checkpoint.copy(providerCallsUsed = checkpoint.providerCallsUsed + 1, inFlight = true, updatedAt = clock())
            AgentPhase.VALIDATION -> checkpoint.copy(providerCallsUsed = checkpoint.providerCallsUsed + 1, validationAttempts = checkpoint.validationAttempts + 1, inFlight = true, updatedAt = clock())
            AgentPhase.INTAKE, AgentPhase.DONE -> null
        }
    }

    private fun failure(checkpoint: AgentCheckpoint, kind: AgentFailureKind): AgentRunResult {
        val retry = kind != AgentFailureKind.WORKFLOW && kind != AgentFailureKind.BUDGET && canSpendExtra(checkpoint)
        val failed = checkpoint.copy(
            runStatus = AgentRunStatus.FAILED,
            inFlight = false,
            retryAllowed = retry,
            failureKind = kind,
            expectedAction = if (retry) RETRY else NEW_TASK,
            updatedAt = clock(),
        )
        return AgentRunResult(failed, failure = kind)
    }

    private fun terminalWorkflow(checkpoint: AgentCheckpoint): AgentRunResult =
        AgentRunResult(checkpoint.copy(runStatus = AgentRunStatus.FAILED, inFlight = false, retryAllowed = false, failureKind = AgentFailureKind.WORKFLOW, expectedAction = NEW_TASK, updatedAt = clock()), failure = AgentFailureKind.WORKFLOW)

    private fun terminalBudget(checkpoint: AgentCheckpoint): AgentRunResult =
        AgentRunResult(checkpoint.copy(runStatus = AgentRunStatus.FAILED, inFlight = false, retryAllowed = false, failureKind = AgentFailureKind.BUDGET, expectedAction = NEW_TASK, updatedAt = clock()), failure = AgentFailureKind.BUDGET)

    private fun invalid(input: AgentRunInput): AgentRunResult = AgentRunResult(requireNotNull(input.checkpoint))
    private fun canSpendExtra(checkpoint: AgentCheckpoint) = checkpoint.extraAttemptsUsed < MAX_EXTRA_ATTEMPTS_PER_RUN && checkpoint.providerCallsUsed < MAX_PROVIDER_CALLS_PER_RUN

    private fun phaseInstruction(checkpoint: AgentCheckpoint): String = when (checkpoint.phase) {
        AgentPhase.PLANNING -> "Return JSON v1 for runId=${checkpoint.runId}, revision=${checkpoint.revision}: PLAN_READY (1..3 steps) or NEEDS_USER."
        AgentPhase.EXECUTION -> if (checkpoint.revisionPending) "Return JSON v1 REVISED_RESULT for runId=${checkpoint.runId}, revision=${checkpoint.revision}; revise only listed issues." else "Return JSON v1 STEP_RESULT for stepId=${checkpoint.plan[checkpoint.currentStepIndex].id}, runId=${checkpoint.runId}, revision=${checkpoint.revision}."
        AgentPhase.VALIDATION -> "Return JSON v1 PASS or REVISE (1..5 issues) for runId=${checkpoint.runId}, revision=${checkpoint.revision}."
        AgentPhase.INTAKE, AgentPhase.DONE -> "No provider call is allowed."
    }

    private fun checkpointContext(checkpoint: AgentCheckpoint): String = buildString {
        append("runId=").append(checkpoint.runId).append('\n')
        append("phase=").append(checkpoint.phase).append('\n')
        checkpoint.plan.forEachIndexed { index, step -> append("step[").append(index).append("]=").append(step.id).append('|').append(step.title).append('\n') }
        checkpoint.acceptedSummaries.forEachIndexed { index, value -> append("summary[").append(index).append("]=").append(value).append('\n') }
        if (checkpoint.candidateResult.isNotBlank()) append("candidate=").append(checkpoint.candidateResult).append('\n')
        if (checkpoint.revisionIssues.isNotEmpty()) append("issues=").append(checkpoint.revisionIssues.joinToString(" | "))
    }

    private fun validateRecoveryInput(input: AgentRunInput) {
        require(input.messages.size <= 80)
        require(input.messages.sumOf { it.content.codePointCount(0, it.content.length) } <= 48_000)
        require(input.messages.all { it.content.codePointCount(0, it.content.length) <= 12_000 })
        require(encode(requireNotNull(input.checkpoint)).length <= 16_384)
    }

    private fun titleOf(messages: List<com.mypersonalassistent.core.history.api.ChatMessage>): String = messages
        .firstOrNull { it.role == com.mypersonalassistent.core.history.api.MessageRole.USER }
        ?.content?.replace(Regex("\\s+"), " ")?.take(60) ?: "Новый чат"

    private fun encode(value: AgentCheckpoint): String = json.encodeToString(CheckpointWire.from(value))
    private fun decode(value: String): AgentCheckpoint = json.decodeFromString<CheckpointWire>(value).toCheckpoint().also(::validateCheckpoint)

    private fun validateCheckpoint(checkpoint: AgentCheckpoint) {
        require(checkpoint.chatId.isNotBlank() && checkpoint.runId.length == 36)
        require(checkpoint.plan.size <= 3 && checkpoint.plan.all { it.id.valid(36) && it.title.valid(120) && it.successCriterion.valid(300) })
        require(checkpoint.currentStepIndex in 0..(checkpoint.plan.size.coerceAtLeast(1) - 1))
        require(checkpoint.acceptedSummaries.size <= 3 && checkpoint.acceptedSummaries.all { it.valid(1_000) })
        require(checkpoint.candidateResult.codePointCount(0, checkpoint.candidateResult.length) <= 8_000)
        require(checkpoint.revisionIssues.size <= 5 && checkpoint.revisionIssues.all { it.valid(400) })
        require(checkpoint.providerCallsUsed in 0..MAX_PROVIDER_CALLS_PER_RUN && checkpoint.extraAttemptsUsed in 0..MAX_EXTRA_ATTEMPTS_PER_RUN)
        require(checkpoint.phase != AgentPhase.DONE || checkpoint.runStatus == AgentRunStatus.COMPLETED)
    }

    private fun String.valid(limit: Int) = isNotBlank() && codePointCount(0, length) <= limit

    private companion object {
        const val WAIT = "Подождите"
        const val ANSWER = "Ответьте на вопрос"
        const val RESUME = "Продолжить"
        const val RETRY = "Повторить"
        const val NEW_TASK = "Напишите новую задачу"
    }
}

@Serializable
private data class CheckpointWire(
    val schemaVersion: Int = 1, val chatId: String, val runId: String, val revision: Long,
    val phase: String, val runStatus: String, val plan: List<PlanWire>, val currentStepIndex: Int,
    val acceptedSummaries: List<String>, val candidateResult: String, val expectedAction: String,
    val providerCallsUsed: Int, val planningCallsUsed: Int, val validationAttempts: Int,
    val extraAttemptsUsed: Int, val retryAllowed: Boolean, val failureKind: String? = null,
    val pausedFromStatus: String? = null, val resumeOperation: String, val inFlight: Boolean,
    val updatedAt: Long, val revisionPending: Boolean = false, val revisionIssues: List<String> = emptyList(),
) {
    fun toCheckpoint(): AgentCheckpoint {
        require(schemaVersion == 1)
        return AgentCheckpoint(chatId, runId, revision, AgentPhase.valueOf(phase), AgentRunStatus.valueOf(runStatus), plan.map { AgentPlanStep(it.id, it.title, it.successCriterion) }, currentStepIndex, acceptedSummaries, candidateResult, expectedAction, providerCallsUsed, planningCallsUsed, validationAttempts, extraAttemptsUsed, retryAllowed, failureKind?.let(AgentFailureKind::valueOf), pausedFromStatus?.let(AgentRunStatus::valueOf), ResumeOperation.valueOf(resumeOperation), inFlight, updatedAt, revisionPending, revisionIssues)
    }
    companion object { fun from(value: AgentCheckpoint) = CheckpointWire(chatId = value.chatId, runId = value.runId, revision = value.revision, phase = value.phase.name, runStatus = value.runStatus.name, plan = value.plan.map { PlanWire(it.id, it.title, it.successCriterion) }, currentStepIndex = value.currentStepIndex, acceptedSummaries = value.acceptedSummaries, candidateResult = value.candidateResult, expectedAction = value.expectedAction, providerCallsUsed = value.providerCallsUsed, planningCallsUsed = value.planningCallsUsed, validationAttempts = value.validationAttempts, extraAttemptsUsed = value.extraAttemptsUsed, retryAllowed = value.retryAllowed, failureKind = value.failureKind?.name, pausedFromStatus = value.pausedFromStatus?.name, resumeOperation = value.resumeOperation.name, inFlight = value.inFlight, updatedAt = value.updatedAt, revisionPending = value.revisionPending, revisionIssues = value.revisionIssues) }
}
@Serializable private data class PlanWire(val id: String, val title: String, val successCriterion: String)
