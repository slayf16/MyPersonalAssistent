package com.mypersonalassistent.core.agent.api

import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import kotlinx.coroutines.flow.StateFlow

/** Local source of truth for one bounded, foreground task run. */
enum class AgentPhase { INTAKE, PLANNING, EXECUTION, VALIDATION, DONE }
enum class AgentRunStatus { ACTIVE, WAITING_USER, WAITING_APPROVAL, STALE_PAUSED, FAILED, REFUSED, COMPLETED, TERMINATED }
enum class ResumeTarget {
    NONE,
    REPEAT_PLANNING_CALL,
    RESTORE_WAITING_ANSWER,
    RESTORE_WAITING_APPROVAL,
    REPEAT_EXECUTION_CALL,
    REPEAT_VALIDATION_CALL,
}
enum class AgentFailureKind { PROVIDER, SCHEMA, CONTEXT, WORKFLOW, BUDGET }

/** Monotonic identity of one serialized engine operation within a chat lane. */
@JvmInline value class AgentOperationToken(val value: Long)

/** User data for exactly one requested replan; never system or invariant policy. */
data class PlanChangeContext(val basePlanRevision: Long, val comment: String)

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
    val inFlight: Boolean = false,
    val updatedAt: Long = 0,
    val revisionPending: Boolean = false,
    val revisionIssues: List<String> = emptyList(),
    /** Immutable policy identity; absent only in pre-v5 legacy recovery and must fail closed. */
    val invariantSnapshot: InvariantSnapshotRef? = null,
    val approvedPlanRevision: Long? = null,
    val approvedAt: Long? = null,
    val staleTarget: ResumeTarget = ResumeTarget.NONE,
    val operationToken: AgentOperationToken = AgentOperationToken(0),
    val planChange: PlanChangeContext? = null,
    val refusal: SafeInvariantRefusal? = null,
)

data class AgentRunInput(
    val chatId: String,
    val messages: List<ChatMessage>,
    val taskMemory: TaskMemory,
    val checkpoint: AgentCheckpoint? = null,
    /** UI echoes the operation it rendered; absence is allowed only for a new run. */
    val expectedOperationToken: AgentOperationToken? = checkpoint?.operationToken,
)

/** Authoritative engine command: it never trusts a feature-local checkpoint copy. */
data class StartNewTask(
    val chatId: String,
    val messages: List<ChatMessage>,
    val taskMemory: TaskMemory,
    val expectedOperationToken: AgentOperationToken? = null,
)

data class AgentRunResult(
    val checkpoint: AgentCheckpoint,
    /** A planning question that the feature must append to the visible transcript. */
    val visibleQuestion: String? = null,
    /** Final output is visible only after an accepted validation PASS. */
    val finalResult: String? = null,
    val failure: AgentFailureKind? = null,
    /** Typed invalid-event result; callers must not turn it into a provider call. */
    val rejectedTransition: AgentTransition.Rejected? = null,
    val refusal: SafeInvariantRefusal? = null,
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
    /** Terminates and closes any authoritative run, then leaves the chat ready for a fresh Send. */
    suspend fun startNewTask(command: StartNewTask): AgentRunResult
    suspend fun answer(input: AgentRunInput): AgentRunResult
    suspend fun retry(input: AgentRunInput): AgentRunResult
    /** Explicit approval is required before execution; stale/double approval is a typed rejection. */
    suspend fun approvePlan(input: AgentRunInput, expectedPlanRevision: Long): AgentRunResult = AgentRunResult(requireNotNull(input.checkpoint))
    suspend fun requestPlanChanges(input: AgentRunInput, context: PlanChangeContext): AgentRunResult = AgentRunResult(requireNotNull(input.checkpoint))
    suspend fun continueWithCurrentRules(input: AgentRunInput): AgentRunResult = AgentRunResult(requireNotNull(input.checkpoint))
    /** Called after a committed policy mutation; must never publish an in-flight late result. */
    suspend fun markStaleAfterPolicyMutation(chatId: String, affectedRunIds: Set<String>): AgentRunResult? = null
    /**
     * Called by the feature only after its run Job is cancelled and joined. It persists an
     * authoritative interrupted checkpoint without starting a provider call or changing budgets.
     */
    suspend fun normalizeInterruptedForRecovery(input: AgentRunInput): AgentRunResult
    suspend fun persist(input: AgentRunInput)
    suspend fun discardRecovery(chatId: String): Boolean
    fun decodeCheckpoint(payload: String): AgentCheckpoint?
}

const val MAX_PROVIDER_CALLS_PER_RUN = 10
const val MAX_EXTRA_ATTEMPTS_PER_RUN = 2
