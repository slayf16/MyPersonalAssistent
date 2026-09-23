package com.mypersonalassistent.core.agent.api

import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.memory.api.TaskMemory
import kotlinx.coroutines.flow.StateFlow

/** Local source of truth for one bounded, foreground task run. */
enum class AgentPhase { INTAKE, PLANNING, EXECUTION, VALIDATION, DONE }
enum class AgentRunStatus { ACTIVE, WAITING_USER, PAUSED, FAILED, COMPLETED }
enum class ResumeOperation { NONE, REPEAT_CALL, RESTORE_WAITING_USER }
enum class AgentFailureKind { PROVIDER, SCHEMA, CONTEXT, WORKFLOW, BUDGET }

data class AgentPlanStep(
    val id: String,
    val title: String,
    val successCriterion: String,
)

data class AgentCheckpoint(
    val chatId: String,
    val runId: String,
    val revision: Long = 0,
    val phase: AgentPhase,
    val runStatus: AgentRunStatus,
    val plan: List<AgentPlanStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val acceptedSummaries: List<String> = emptyList(),
    val candidateResult: String = "",
    val expectedAction: String = "",
    val providerCallsUsed: Int = 0,
    val planningCallsUsed: Int = 0,
    val validationAttempts: Int = 0,
    val extraAttemptsUsed: Int = 0,
    val retryAllowed: Boolean = false,
    val failureKind: AgentFailureKind? = null,
    val pausedFromStatus: AgentRunStatus? = null,
    val resumeOperation: ResumeOperation = ResumeOperation.NONE,
    val inFlight: Boolean = false,
    val updatedAt: Long = 0,
    val revisionPending: Boolean = false,
    val revisionIssues: List<String> = emptyList(),
)

data class AgentRunInput(
    val chatId: String,
    val messages: List<ChatMessage>,
    val taskMemory: TaskMemory,
    val checkpoint: AgentCheckpoint? = null,
)

data class AgentRunResult(
    val checkpoint: AgentCheckpoint,
    /** A planning question that the feature must append to the visible transcript. */
    val visibleQuestion: String? = null,
    /** Final output is visible only after an accepted validation PASS. */
    val finalResult: String? = null,
    val failure: AgentFailureKind? = null,
)

/**
 * Runs only the deterministic, bounded workflow. Lifecycle ownership and the single Job stay
 * in feature:chat; this API is deliberately free of Room, Ktor and UI types.
 */
interface AgentRunEngine {
    /**
     * The latest checkpoint accepted by the engine for the active operation. Consumers use this
     * for progress only; they must not manufacture or mutate a competing checkpoint.
     */
    val checkpoint: StateFlow<AgentCheckpoint?>
    suspend fun start(input: AgentRunInput): AgentRunResult
    suspend fun answer(input: AgentRunInput): AgentRunResult
    suspend fun resume(input: AgentRunInput): AgentRunResult
    suspend fun retry(input: AgentRunInput): AgentRunResult
    /**
     * Pauses the engine's current checkpoint for [input.chatId]. [AgentRunInput.checkpoint] is
     * deliberately ignored here so a delayed UI projection cannot roll back run identity or
     * already charged budgets. Messages and task memory are still supplied for recovery.
     */
    suspend fun pause(input: AgentRunInput): AgentCheckpoint
    suspend fun persist(input: AgentRunInput)
    suspend fun discardRecovery(chatId: String): Boolean
    fun decodeCheckpoint(payload: String): AgentCheckpoint?
}

const val MAX_PROVIDER_CALLS_PER_RUN = 10
const val MAX_EXTRA_ATTEMPTS_PER_RUN = 2
