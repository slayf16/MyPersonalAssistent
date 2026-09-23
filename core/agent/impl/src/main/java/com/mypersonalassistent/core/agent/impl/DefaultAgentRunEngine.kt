package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentFailureKind
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentPlanStep
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunResult
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.agent.api.MAX_EXTRA_ATTEMPTS_PER_RUN
import com.mypersonalassistent.core.agent.api.MAX_PROVIDER_CALLS_PER_RUN
import com.mypersonalassistent.core.agent.api.ResumeTarget
import com.mypersonalassistent.core.agent.api.StartNewTask
import com.mypersonalassistent.core.agent.api.PlanChangeContext
import com.mypersonalassistent.core.agent.api.AgentOperationToken
import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.invariants.api.GateOutcome
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantGuard
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.SnapshotResult
import com.mypersonalassistent.core.agent.api.AgentEvent
import com.mypersonalassistent.core.agent.api.AgentTransition
import com.mypersonalassistent.core.agent.api.AgentTransitionReducer
import com.mypersonalassistent.core.agent.api.CanonicalAgentState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class DefaultAgentRunEngine(
    private val llm: Llm,
    private val composer: AgentRequestComposer,
    private val history: HistoryRepository,
    private val recovery: AgentRecoveryRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val invariants: InvariantRepository? = null,
    private val guard: InvariantGuard? = null,
) : AgentRunEngine {
    private val mutableCheckpoint = MutableStateFlow<AgentCheckpoint?>(null)
    private val latestInputs = mutableMapOf<String, AgentRunInput>()
    /** Watermark rejects a completion from any invalidated operation without shared refusal state. */
    private val operationWatermarks = ConcurrentHashMap<String, AtomicLong>()
    /** One engine-owned lane covers every state-changing command for every chat. */
    private val commandLane = Mutex()
    override val checkpoint: StateFlow<AgentCheckpoint?> = mutableCheckpoint.asStateFlow()

    override suspend fun start(input: AgentRunInput): AgentRunResult {
        return commandLane.withLock {
        checkpoint.value?.takeIf { it.chatId == input.chatId }?.let { current ->
            return rejected(current, AgentEvent.START)
        }
        if (AgentTransitionReducer.reduce(CanonicalAgentState.IDLE, AgentEvent.START) is AgentTransition.Rejected) {
            return AgentRunResult(terminalInvariantFailure(input))
        }
        latestInputs[input.chatId] = input
        val snapshot = when (val result = invariants?.createSnapshot()) {
            null -> null
            is SnapshotResult.Available -> result.snapshot
            SnapshotResult.Unavailable -> return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        }
        if (snapshot != null) when (val outcome = gate(InvariantGateStage.REQUEST, snapshot, input.messages.lastOrNull()?.content.orEmpty())) {
            is GateOutcome.Rejected -> return AgentRunResult(refused(input, snapshot.ref(), outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal)
            GateOutcome.Unavailable -> return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
            is GateOutcome.Allowed -> Unit
        }
        val checkpoint = AgentCheckpoint(
            chatId = input.chatId,
            runId = UUID.randomUUID().toString(),
            phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.ACTIVE,
            expectedAction = WAIT,
            updatedAt = clock(),
            invariantSnapshot = snapshot?.ref(),
            operationToken = nextToken(input.chatId),
        )
        advance(input.copy(checkpoint = checkpoint), checkpoint)
        }
    }

    override suspend fun startNewTask(command: StartNewTask): AgentRunResult {
        return commandLane.withLock {
        val current = checkpoint.value?.takeIf { it.chatId == command.chatId }
        if (current == null) {
            return AgentRunResult(
                AgentCheckpoint(
                    chatId = command.chatId,
                    runId = UUID.randomUUID().toString(),
                    phase = AgentPhase.INTAKE,
                    runStatus = AgentRunStatus.TERMINATED,
                    expectedAction = NEW_TASK,
                    updatedAt = clock(),
                    operationToken = nextToken(command.chatId),
                ),
            )
        }
        if (command.expectedOperationToken != null && command.expectedOperationToken != current.operationToken) {
            return rejected(current, AgentEvent.NEW_TASK)
        }
        val terminated = current.copy(
            runStatus = AgentRunStatus.TERMINATED,
            inFlight = false,
            retryAllowed = false,
            expectedAction = NEW_TASK,
            operationToken = nextToken(command.chatId),
            revision = current.revision + 1,
            updatedAt = clock(),
        )
        persist(AgentRunInput(command.chatId, command.messages, command.taskMemory, terminated))
        recovery.discardRecovery(command.chatId)
        mutableCheckpoint.value = null
        latestInputs.remove(command.chatId)
        AgentRunResult(terminated)
        }
    }

    override suspend fun answer(input: AgentRunInput): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        val waitingForPlanningAnswer = checkpoint.phase == AgentPhase.PLANNING && checkpoint.runStatus == AgentRunStatus.WAITING_USER
        val interruptedActive = checkpoint.runStatus == AgentRunStatus.ACTIVE && !checkpoint.inFlight && checkpoint.phase in setOf(AgentPhase.PLANNING, AgentPhase.EXECUTION, AgentPhase.VALIDATION)
        if ((!waitingForPlanningAnswer && !interruptedActive) || !isAllowed(checkpoint, AgentEvent.SUBMIT_ANSWER)) return invalid(input, AgentEvent.SUBMIT_ANSWER)
        revalidateForContinuation(input, checkpoint)?.let { return it }
        if (interruptedActive && !canSpendExtra(checkpoint)) return terminalBudget(checkpoint)
        advance(input, checkpoint.copy(
            runStatus = AgentRunStatus.ACTIVE,
            extraAttemptsUsed = checkpoint.extraAttemptsUsed + if (interruptedActive) 1 else 0,
            expectedAction = WAIT,
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        ))
        }
    }

    override suspend fun retry(input: AgentRunInput): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        revalidateForContinuation(input, checkpoint)?.let { return it }
        if (checkpoint.runStatus != AgentRunStatus.FAILED || !checkpoint.retryAllowed || !isAllowed(checkpoint, AgentEvent.RETRY)) return invalid(input, AgentEvent.RETRY)
        if (!canSpendExtra(checkpoint)) return terminalBudget(checkpoint)
        advance(input, checkpoint.copy(
            runStatus = AgentRunStatus.ACTIVE,
            retryAllowed = false,
            failureKind = null,
            extraAttemptsUsed = checkpoint.extraAttemptsUsed + 1,
            expectedAction = WAIT,
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        ))
        }
    }

    override suspend fun approvePlan(input: AgentRunInput, expectedPlanRevision: Long): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        revalidateForContinuation(input, checkpoint)?.let { return it }
        if (checkpoint.runStatus != AgentRunStatus.WAITING_APPROVAL || checkpoint.revision != expectedPlanRevision || !isAllowed(checkpoint, AgentEvent.APPROVE_PLAN)) return invalid(input, AgentEvent.APPROVE_PLAN)
        advance(input, checkpoint.copy(
            phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.ACTIVE,
            approvedPlanRevision = expectedPlanRevision, approvedAt = clock(), expectedAction = WAIT,
            revision = checkpoint.revision + 1, updatedAt = clock(),
        ))
        }
    }

    override suspend fun requestPlanChanges(input: AgentRunInput, context: PlanChangeContext): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        revalidateForContinuation(input, checkpoint)?.let { return it }
        if (checkpoint.runStatus != AgentRunStatus.WAITING_APPROVAL || checkpoint.revision != context.basePlanRevision || context.comment.trim().codePointCount(0, context.comment.trim().length) !in 1..2000 || !isAllowed(checkpoint, AgentEvent.CHANGE_PLAN)) return invalid(input, AgentEvent.CHANGE_PLAN)
        advance(input, checkpoint.replan(snapshot = checkpoint.invariantSnapshot, revision = checkpoint.revision + 1).copy(planChange = context.copy(comment = context.comment.trim())))
        }
    }

    override suspend fun continueWithCurrentRules(input: AgentRunInput): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return invalid(input)
        if (checkpoint.runStatus != AgentRunStatus.STALE_PAUSED || !isAllowed(checkpoint, AgentEvent.CONTINUE_CURRENT_RULES)) return invalid(input, AgentEvent.CONTINUE_CURRENT_RULES)
        val repository = invariants ?: return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        val prior = checkpoint.invariantSnapshot ?: return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        if (repository.readSnapshot(prior) !is SnapshotResult.Available) return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        val snapshot = repository.createSnapshot()
        if (snapshot !is SnapshotResult.Available) return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        when (val outcome = gate(InvariantGateStage.REQUEST, snapshot.snapshot, input.messages.lastOrNull()?.content.orEmpty())) {
            is GateOutcome.Rejected -> return AgentRunResult(refused(checkpoint.copy(invariantSnapshot = snapshot.snapshot.ref()), outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal)
            GateOutcome.Unavailable -> return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
            is GateOutcome.Allowed -> Unit
        }
        advance(input, checkpoint.replan(snapshot = snapshot.snapshot.ref(), revision = checkpoint.revision + 1))
        }
    }

    override suspend fun markStaleAfterPolicyMutation(chatId: String, affectedRunIds: Set<String>): AgentRunResult? {
        return commandLane.withLock {
        val current = checkpoint.value ?: return null
        if (current.chatId != chatId || (affectedRunIds.isNotEmpty() && current.runId !in affectedRunIds && chatId !in affectedRunIds)) return null
        if (!isAllowed(current, AgentEvent.POLICY_CHANGED)) return rejected(current, AgentEvent.POLICY_CHANGED)
        val stale = current.copy(runStatus = AgentRunStatus.STALE_PAUSED, inFlight = false, staleTarget = targetFor(current), expectedAction = CONTINUE_CURRENT_RULES, operationToken = nextToken(chatId), revision = current.revision + 1, updatedAt = clock())
        latestInputs[chatId]?.let { persist(it.copy(checkpoint = stale)) }
        publish(stale)
        AgentRunResult(stale)
        }
    }

    override suspend fun normalizeInterruptedForRecovery(input: AgentRunInput): AgentRunResult {
        return commandLane.withLock {
        val checkpoint = currentCheckpoint(input) ?: return AgentRunResult(requireNotNull(input.checkpoint))
        val normalized = if (checkpoint.runStatus == AgentRunStatus.ACTIVE || checkpoint.inFlight) checkpoint.copy(
            inFlight = false,
            expectedAction = CONTINUE,
            operationToken = nextToken(checkpoint.chatId),
            revision = checkpoint.revision + 1,
            updatedAt = clock(),
        ) else checkpoint
        persist(input.copy(checkpoint = normalized))
        AgentRunResult(normalized)
        }
    }

    override suspend fun persist(input: AgentRunInput) {
        val checkpoint = requireNotNull(input.checkpoint)
        latestInputs[input.chatId] = input
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
        checkpoint.invariantSnapshot?.let { ref ->
            val indexed = invariants?.trackRun(
                chatId = checkpoint.chatId,
                runId = checkpoint.runId,
                snapshotRef = ref,
                isNonterminal = checkpoint.runStatus !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.REFUSED, AgentRunStatus.TERMINATED),
                isActive = checkpoint.runStatus == AgentRunStatus.ACTIVE,
                isStale = checkpoint.runStatus == AgentRunStatus.STALE_PAUSED,
                staleTarget = checkpoint.staleTarget.name,
            )
            if (indexed == false) throw IllegalStateException("Invariant run-index write failed")
        }
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
        var checkpoint = initial.copy(operationToken = nextToken(initial.chatId))
        while (checkpoint.runStatus == AgentRunStatus.ACTIVE) {
            val request = try {
                val invariantSnapshot = checkpoint.invariantSnapshot?.let { readSnapshot(it) }
                if (invariants != null && invariantSnapshot == null) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                if (invariantSnapshot == null) composer.composeForTask(
                    input.chatId,
                    input.messages,
                    input.taskMemory,
                    checkpointContext(checkpoint),
                    phaseInstruction(checkpoint),
                ) else composer.composeForTaskWithInvariants(
                    input.chatId, input.messages, input.taskMemory, checkpointContext(checkpoint), phaseInstruction(checkpoint), invariantSnapshot,
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
                                providerRejection(checkpoint, AgentEvent.PLAN_READY)?.let { return it.also { result -> publish(result.checkpoint) } }
                                if (checkpoint.planningCallsUsed > 2) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                val snapshot = checkpoint.invariantSnapshot?.let { readSnapshot(it) }
                                if (invariants != null && snapshot == null) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                if (snapshot != null) {
                                    when (val outcome = gate(InvariantGateStage.PLAN, snapshot, event.steps.joinToString("\n") { it.title + "|" + it.successCriterion })) {
                                        is GateOutcome.Rejected -> return AgentRunResult(refused(checkpoint, outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal).also { publish(it.checkpoint) }
                                        GateOutcome.Unavailable -> return failure(checkpoint, AgentFailureKind.CONTEXT).also { publish(it.checkpoint) }
                                        is GateOutcome.Allowed -> Unit
                                    }
                                    return AgentRunResult(checkpoint.copy(
                                        phase = AgentPhase.PLANNING,
                                        runStatus = AgentRunStatus.WAITING_APPROVAL,
                                        plan = event.steps,
                                        currentStepIndex = 0,
                                        inFlight = false,
                                        expectedAction = APPROVE,
                                        planChange = null,
                                        revision = checkpoint.revision + 1,
                                        updatedAt = clock(),
                                    )).also { publish(it.checkpoint) }
                                }
                                checkpoint = checkpoint.copy(
                                    phase = AgentPhase.EXECUTION,
                                    plan = event.steps,
                                    currentStepIndex = 0,
                                    inFlight = false,
                                    expectedAction = WAIT,
                                    planChange = null,
                                    revision = checkpoint.revision + 1,
                                    updatedAt = clock(),
                                )
                            }
                            is EnvelopeResult.Question -> {
                                providerRejection(checkpoint, AgentEvent.PLANNING_QUESTION)?.let { return it.also { result -> publish(result.checkpoint) } }
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
                                val stepEvent = if (checkpoint.revisionPending || checkpoint.currentStepIndex == checkpoint.plan.lastIndex) AgentEvent.LAST_STEP_ACCEPTED else AgentEvent.STEP_ACCEPTED
                                providerRejection(checkpoint, stepEvent)?.let { return it.also { result -> publish(result.checkpoint) } }
                                if (event.revised != checkpoint.revisionPending || (!event.revised && event.stepId != checkpoint.plan.getOrNull(checkpoint.currentStepIndex)?.id)) {
                                    return failure(checkpoint, AgentFailureKind.SCHEMA).also { publish(it.checkpoint) }
                                }
                                val snapshot = checkpoint.invariantSnapshot?.let { readSnapshot(it) }
                                if (invariants != null && snapshot == null) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                if (snapshot != null) when (val outcome = gate(InvariantGateStage.STEP, snapshot, event.artifact)) {
                                    is GateOutcome.Rejected -> return AgentRunResult(refused(checkpoint, outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal).also { publish(it.checkpoint) }
                                    GateOutcome.Unavailable -> return failure(checkpoint, AgentFailureKind.CONTEXT).also { publish(it.checkpoint) }
                                    is GateOutcome.Allowed -> Unit
                                }
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
                                providerRejection(checkpoint, AgentEvent.VALIDATION_PASS)?.let { return it.also { result -> publish(result.checkpoint) } }
                                val snapshot = checkpoint.invariantSnapshot?.let { readSnapshot(it) }
                                if (invariants != null && snapshot == null) return terminalWorkflow(checkpoint).also { publish(it.checkpoint) }
                                if (snapshot != null) when (val outcome = gate(InvariantGateStage.FINAL, snapshot, checkpoint.candidateResult)) {
                                    is GateOutcome.Rejected -> return AgentRunResult(refused(checkpoint, outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal).also { publish(it.checkpoint) }
                                    GateOutcome.Unavailable -> return failure(checkpoint, AgentFailureKind.CONTEXT).also { publish(it.checkpoint) }
                                    is GateOutcome.Allowed -> Unit
                                }
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
                                providerRejection(checkpoint, AgentEvent.VALIDATION_REVISE)?.let { return it.also { result -> publish(result.checkpoint) } }
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
        val current = checkpoint.value?.takeIf { current ->
            current.chatId == input.chatId && (requested == null || current.runId == requested.runId)
        } ?: requested
        return current?.takeIf { checkpoint ->
            input.expectedOperationToken == null ||
                input.expectedOperationToken == checkpoint.operationToken
        }
    }

    private fun publish(next: AgentCheckpoint) {
        val watermark = operationWatermarks[next.chatId]?.get() ?: 0
        if (next.operationToken.value < watermark) return
        val previous = mutableCheckpoint.value
        if (previous == null || previous.chatId != next.chatId || previous.runId != next.runId ||
            (next.operationToken.value >= previous.operationToken.value && next.revision >= previous.revision)) {
            mutableCheckpoint.value = next
        }
    }

    private fun nextToken(chatId: String): AgentOperationToken = AgentOperationToken(
        operationWatermarks.computeIfAbsent(chatId) { AtomicLong(0) }.incrementAndGet(),
    )

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
    private fun invalid(input: AgentRunInput, event: AgentEvent): AgentRunResult {
        val checkpoint = requireNotNull(input.checkpoint)
        val transition = AgentTransitionReducer.reduce(canonicalState(checkpoint), event) as AgentTransition.Rejected
        return AgentRunResult(checkpoint, rejectedTransition = transition)
    }
    private fun isAllowed(checkpoint: AgentCheckpoint, event: AgentEvent): Boolean =
        AgentTransitionReducer.reduce(canonicalState(checkpoint), event) is AgentTransition.Applied
    /** Provider envelopes are subject to the same typed FSM gate as user actions. */
    private fun providerRejection(checkpoint: AgentCheckpoint, event: AgentEvent): AgentRunResult? {
        val transition = AgentTransitionReducer.reduce(canonicalState(checkpoint), event)
        return (transition as? AgentTransition.Rejected)?.let { rejected ->
            AgentRunResult(checkpoint, rejectedTransition = rejected)
        }
    }
    private fun rejected(checkpoint: AgentCheckpoint, event: AgentEvent): AgentRunResult {
        val transition = AgentTransitionReducer.reduce(canonicalState(checkpoint), event) as AgentTransition.Rejected
        return AgentRunResult(checkpoint, rejectedTransition = transition)
    }
    private fun canonicalState(checkpoint: AgentCheckpoint): CanonicalAgentState = when (checkpoint.runStatus) {
        AgentRunStatus.WAITING_APPROVAL -> CanonicalAgentState.P_WAIT_APPROVAL
        AgentRunStatus.WAITING_USER -> CanonicalAgentState.P_WAIT_ANSWER
        AgentRunStatus.STALE_PAUSED -> CanonicalAgentState.STALE_PAUSED
        AgentRunStatus.REFUSED -> CanonicalAgentState.REFUSED
        AgentRunStatus.COMPLETED -> CanonicalAgentState.DONE
        AgentRunStatus.TERMINATED -> CanonicalAgentState.TERMINATED
        AgentRunStatus.FAILED -> if (checkpoint.retryAllowed) CanonicalAgentState.FAILED_RETRYABLE else CanonicalAgentState.FAILED_TERMINAL
        AgentRunStatus.ACTIVE -> when (checkpoint.phase) { AgentPhase.PLANNING -> CanonicalAgentState.P_ACTIVE; AgentPhase.EXECUTION -> CanonicalAgentState.E_ACTIVE; AgentPhase.VALIDATION -> CanonicalAgentState.V_ACTIVE; else -> CanonicalAgentState.IDLE }
    }
    private fun targetFor(checkpoint: AgentCheckpoint): ResumeTarget = when {
        checkpoint.runStatus == AgentRunStatus.WAITING_USER -> ResumeTarget.RESTORE_WAITING_ANSWER
        checkpoint.runStatus == AgentRunStatus.WAITING_APPROVAL -> ResumeTarget.RESTORE_WAITING_APPROVAL
        checkpoint.phase == AgentPhase.PLANNING -> ResumeTarget.REPEAT_PLANNING_CALL
        checkpoint.phase == AgentPhase.EXECUTION -> ResumeTarget.REPEAT_EXECUTION_CALL
        checkpoint.phase == AgentPhase.VALIDATION -> ResumeTarget.REPEAT_VALIDATION_CALL
        else -> ResumeTarget.NONE
    }
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
        checkpoint.planChange?.let {
            append("plan_change_base_revision=").append(it.basePlanRevision).append('\n')
            append("plan_change_comment=").append(it.comment).append('\n')
        }
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
    private fun decode(value: String): AgentCheckpoint = json.decodeFromString<CheckpointWire>(value)
        .toCheckpoint()
        .let(::normalizeDecodedCheckpoint)
        .also(::validateCheckpoint)

    /** Decoding/reopening is inert: an interrupted provider call waits for the next normal Send. */
    private fun normalizeDecodedCheckpoint(checkpoint: AgentCheckpoint): AgentCheckpoint =
        if (checkpoint.runStatus == AgentRunStatus.ACTIVE && checkpoint.inFlight) {
            checkpoint.copy(inFlight = false, expectedAction = CONTINUE)
        } else checkpoint

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
        const val CONTINUE = "Напишите сообщение, чтобы продолжить"
        const val CONTINUE_CURRENT_RULES = "Продолжить с текущими правилами"
        const val RETRY = "Повторить"
        const val APPROVE = "Утвердить план"
        const val NEW_TASK = "Напишите новую задачу"
    }

    private suspend fun readSnapshot(ref: InvariantSnapshotRef) = when (val result = invariants?.readSnapshot(ref)) {
        is SnapshotResult.Available -> result.snapshot
        else -> null
    }
    /** Every nonterminal continuation rechecks immutable snapshot integrity and current policy revision. */
    private suspend fun revalidateForContinuation(input: AgentRunInput, checkpoint: AgentCheckpoint): AgentRunResult? {
        val repository = invariants ?: return null
        val ref = checkpoint.invariantSnapshot ?: return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        if (repository.readSnapshot(ref) !is SnapshotResult.Available) return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        if (repository.collectionRevision() == ref.collectionRevision) return null
        val fresh = repository.createSnapshot()
        if (fresh !is SnapshotResult.Available) return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
        when (val outcome = gate(InvariantGateStage.REQUEST, fresh.snapshot, input.messages.lastOrNull()?.content.orEmpty())) {
            is GateOutcome.Rejected -> return AgentRunResult(refused(checkpoint.copy(invariantSnapshot = fresh.snapshot.ref()), outcome.refusal), failure = AgentFailureKind.WORKFLOW, refusal = outcome.refusal)
            GateOutcome.Unavailable -> return AgentRunResult(terminalInvariantFailure(input), failure = AgentFailureKind.CONTEXT)
            is GateOutcome.Allowed -> Unit
        }
        val replanning = checkpoint.replan(fresh.snapshot.ref(), checkpoint.revision + 1)
        return advance(input.copy(checkpoint = replanning), replanning)
    }
    private fun AgentCheckpoint.replan(snapshot: InvariantSnapshotRef?, revision: Long) = copy(
        phase = AgentPhase.PLANNING, runStatus = AgentRunStatus.ACTIVE, plan = emptyList(), currentStepIndex = 0,
        acceptedSummaries = emptyList(), candidateResult = "", revisionPending = false, revisionIssues = emptyList(),
        approvedPlanRevision = null, approvedAt = null, invariantSnapshot = snapshot, staleTarget = ResumeTarget.NONE,
        operationToken = AgentOperationToken(0), planChange = null, revision = revision, updatedAt = clock(), expectedAction = WAIT,
    )
    private suspend fun gate(stage: InvariantGateStage, snapshot: com.mypersonalassistent.core.invariants.api.InvariantSnapshot, artifact: String): GateOutcome = when (val result = guard?.check(stage, snapshot, artifact)) {
        null -> GateOutcome.Allowed(snapshot.ref(), digest(artifact))
        is GateOutcome.Allowed -> if (result.snapshotRef == snapshot.ref() && result.artifactDigest == digest(artifact)) result else GateOutcome.Unavailable
        is GateOutcome.Rejected -> result
        GateOutcome.Unavailable -> GateOutcome.Unavailable
    }
    private fun com.mypersonalassistent.core.invariants.api.InvariantSnapshot.ref() = InvariantSnapshotRef(id, collectionRevision, contentDigest)
    private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun terminalInvariantFailure(input: AgentRunInput): AgentCheckpoint = AgentCheckpoint(input.chatId, UUID.randomUUID().toString(), phase = AgentPhase.PLANNING, runStatus = AgentRunStatus.FAILED, failureKind = AgentFailureKind.CONTEXT, expectedAction = NEW_TASK, updatedAt = clock())
    private fun refused(input: AgentRunInput, ref: InvariantSnapshotRef, refusal: SafeInvariantRefusal): AgentCheckpoint = AgentCheckpoint(input.chatId, UUID.randomUUID().toString(), phase = AgentPhase.PLANNING, runStatus = AgentRunStatus.REFUSED, invariantSnapshot = ref, failureKind = AgentFailureKind.WORKFLOW, expectedAction = NEW_TASK, updatedAt = clock(), operationToken = AgentOperationToken(1), refusal = refusal)
    private fun refused(checkpoint: AgentCheckpoint, refusal: SafeInvariantRefusal): AgentCheckpoint = checkpoint.copy(runStatus = AgentRunStatus.REFUSED, inFlight = false, failureKind = AgentFailureKind.WORKFLOW, expectedAction = NEW_TASK, revision = checkpoint.revision + 1, updatedAt = clock(), refusal = refusal)
}

@Serializable
private data class CheckpointWire(
    val schemaVersion: Int = 4, val chatId: String, val runId: String, val revision: Long,
    val phase: String, val runStatus: String, val plan: List<PlanWire>, val currentStepIndex: Int,
    val acceptedSummaries: List<String>, val candidateResult: String, val expectedAction: String,
    val providerCallsUsed: Int, val planningCallsUsed: Int, val validationAttempts: Int,
    val extraAttemptsUsed: Int, val retryAllowed: Boolean, val failureKind: String? = null,
    /** v3 read-only fields; v4 writes omit them. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pausedFromStatus: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resumeTarget: String? = null,
    val inFlight: Boolean,
    val updatedAt: Long, val revisionPending: Boolean = false, val revisionIssues: List<String> = emptyList(),
    val invariantSnapshotId: String? = null, val invariantCollectionRevision: Long? = null, val invariantDigest: String? = null,
    val approvedPlanRevision: Long? = null, val approvedAt: Long? = null, val staleTarget: String = ResumeTarget.NONE.name,
    val operationToken: Long = 0, val planChangeBaseRevision: Long? = null, val planChangeComment: String? = null,
    val refusalTitle: String? = null, val refusalCategory: String? = null, val refusalRuleId: String? = null, val refusalExplanation: String? = null,
) {
    fun toCheckpoint(): AgentCheckpoint {
        require(schemaVersion in 3..4)
        val snapshot = if (invariantSnapshotId == null && invariantCollectionRevision == null && invariantDigest == null) {
            null
        } else InvariantSnapshotRef(
            com.mypersonalassistent.core.invariants.api.InvariantSnapshotId(requireNotNull(invariantSnapshotId)),
            com.mypersonalassistent.core.invariants.api.CollectionRevision(requireNotNull(invariantCollectionRevision)),
            requireNotNull(invariantDigest),
        )
        val refusal = refusalExplanation?.let { SafeInvariantRefusal(refusalTitle, refusalCategory?.let(com.mypersonalassistent.core.invariants.api.InvariantCategory::valueOf), refusalRuleId?.let { id -> com.mypersonalassistent.core.invariants.api.InvariantRuleId(id) }, it) }
        val planChange = if (planChangeBaseRevision == null && planChangeComment == null) null else PlanChangeContext(requireNotNull(planChangeBaseRevision), requireNotNull(planChangeComment))
        val checkpoint = AgentCheckpoint(chatId, runId, revision, AgentPhase.valueOf(phase), runStatus = if (runStatus == LEGACY_PAUSED) AgentRunStatus.ACTIVE else AgentRunStatus.valueOf(runStatus), plan = plan.map { AgentPlanStep(it.id, it.title, it.successCriterion) }, currentStepIndex = currentStepIndex, acceptedSummaries = acceptedSummaries, candidateResult = candidateResult, expectedAction = expectedAction, providerCallsUsed = providerCallsUsed, planningCallsUsed = planningCallsUsed, validationAttempts = validationAttempts, extraAttemptsUsed = extraAttemptsUsed, retryAllowed = retryAllowed, failureKind = failureKind?.let(AgentFailureKind::valueOf), inFlight = inFlight, updatedAt = updatedAt, revisionPending = revisionPending, revisionIssues = revisionIssues, invariantSnapshot = snapshot, approvedPlanRevision = approvedPlanRevision, approvedAt = approvedAt, staleTarget = ResumeTarget.valueOf(staleTarget), operationToken = AgentOperationToken(operationToken), planChange = planChange, refusal = refusal)
        return if (runStatus == LEGACY_PAUSED) checkpoint.fromLegacyPaused(requireNotNull(resumeTarget)) else checkpoint
    }
    private fun AgentCheckpoint.fromLegacyPaused(targetValue: String): AgentCheckpoint = when (ResumeTarget.valueOf(targetValue)) {
        ResumeTarget.RESTORE_WAITING_ANSWER -> {
            require(phase == AgentPhase.PLANNING)
            copy(runStatus = AgentRunStatus.WAITING_USER, inFlight = false, expectedAction = "Ответьте на вопрос")
        }
        ResumeTarget.RESTORE_WAITING_APPROVAL -> {
            require(phase == AgentPhase.PLANNING)
            copy(runStatus = AgentRunStatus.WAITING_APPROVAL, inFlight = false, expectedAction = "Утвердите план")
        }
        ResumeTarget.REPEAT_PLANNING_CALL -> repeatInterrupted(AgentPhase.PLANNING)
        ResumeTarget.REPEAT_EXECUTION_CALL -> repeatInterrupted(AgentPhase.EXECUTION)
        ResumeTarget.REPEAT_VALIDATION_CALL -> repeatInterrupted(AgentPhase.VALIDATION)
        ResumeTarget.NONE -> throw IllegalArgumentException("Legacy PAUSED checkpoint has no target")
    }
    private fun AgentCheckpoint.repeatInterrupted(expectedPhase: AgentPhase): AgentCheckpoint {
        require(phase == expectedPhase)
        return copy(runStatus = AgentRunStatus.ACTIVE, inFlight = false, expectedAction = "Напишите сообщение, чтобы продолжить")
    }
    companion object {
        private const val LEGACY_PAUSED = "PAUSED"
        fun from(value: AgentCheckpoint) = CheckpointWire(schemaVersion = 4, chatId = value.chatId, runId = value.runId, revision = value.revision, phase = value.phase.name, runStatus = value.runStatus.name, plan = value.plan.map { PlanWire(it.id, it.title, it.successCriterion) }, currentStepIndex = value.currentStepIndex, acceptedSummaries = value.acceptedSummaries, candidateResult = value.candidateResult, expectedAction = value.expectedAction, providerCallsUsed = value.providerCallsUsed, planningCallsUsed = value.planningCallsUsed, validationAttempts = value.validationAttempts, extraAttemptsUsed = value.extraAttemptsUsed, retryAllowed = value.retryAllowed, failureKind = value.failureKind?.name, inFlight = value.inFlight, updatedAt = value.updatedAt, revisionPending = value.revisionPending, revisionIssues = value.revisionIssues, invariantSnapshotId = value.invariantSnapshot?.id?.value, invariantCollectionRevision = value.invariantSnapshot?.collectionRevision?.value, invariantDigest = value.invariantSnapshot?.contentDigest, approvedPlanRevision = value.approvedPlanRevision, approvedAt = value.approvedAt, staleTarget = value.staleTarget.name, operationToken = value.operationToken.value, planChangeBaseRevision = value.planChange?.basePlanRevision, planChangeComment = value.planChange?.comment, refusalTitle = value.refusal?.title, refusalCategory = value.refusal?.category?.name, refusalRuleId = value.refusal?.ruleId?.value, refusalExplanation = value.refusal?.explanation)
    }
}
@Serializable private data class PlanWire(val id: String, val title: String, val successCriterion: String)
