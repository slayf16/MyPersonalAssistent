package com.mypersonalassistent.feature.chat.api

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.memory.api.TaskMemory

data class TaskMemoryDraft(
    val goal: String = "",
    val constraints: String = "",
    val desiredResult: String = "",
    val decisions: String = "",
)
enum class AgentPrimaryAction { NONE, RETRY, START_NEW_TASK, APPROVE_PLAN, CONTINUE_WITH_CURRENT_RULES, OPEN_INVARIANTS }

/** Presentation-only view derived solely from the persisted workflow checkpoint. */
data class AgentUiState(
    val phaseLabel: String,
    val stepLabel: String,
    val expectedAction: String,
    val primaryAction: AgentPrimaryAction,
    val actionContentDescription: String? = null,
)

/**
 * The only user-facing invariant refusal sentence. A missing safe identifier is not
 * substituted with inferred/raw content: the caller must render no refusal sentence
 * until the persisted contract provides all safe metadata.
 */
fun SafeInvariantRefusal.toExactRefusalMessage(): String? {
    val safeTitle = title ?: return null
    val safeCategory = category ?: return null
    val safeRuleId = ruleId ?: return null
    return "Не могу продолжить: результат противоречит обязательному правилу “$safeTitle” (${safeCategory.name}, ${safeRuleId.value}). $explanation"
}

fun AgentCheckpoint?.toUiState(refusal: SafeInvariantRefusal? = this?.refusal): AgentUiState {
    if (this == null) return AgentUiState("Готов", "", "Напишите новую задачу", AgentPrimaryAction.NONE)
    val phase = when (phase) {
        AgentPhase.INTAKE -> "Задача"
        AgentPhase.PLANNING -> "Планирование"
        AgentPhase.EXECUTION -> "Выполнение"
        AgentPhase.VALIDATION -> "Проверка"
        AgentPhase.DONE -> "Готово"
    }
    val step = plan.getOrNull(currentStepIndex)?.let { "Шаг ${currentStepIndex + 1}/${plan.size}: ${it.title}" }.orEmpty()
    val action = when (runStatus) {
        AgentRunStatus.ACTIVE -> AgentPrimaryAction.NONE
        AgentRunStatus.WAITING_APPROVAL -> AgentPrimaryAction.APPROVE_PLAN
        AgentRunStatus.STALE_PAUSED -> AgentPrimaryAction.CONTINUE_WITH_CURRENT_RULES
        AgentRunStatus.REFUSED -> AgentPrimaryAction.OPEN_INVARIANTS
        AgentRunStatus.TERMINATED -> AgentPrimaryAction.START_NEW_TASK
        AgentRunStatus.WAITING_USER -> AgentPrimaryAction.NONE
        AgentRunStatus.FAILED -> if (retryAllowed) AgentPrimaryAction.RETRY else AgentPrimaryAction.START_NEW_TASK
        AgentRunStatus.COMPLETED -> AgentPrimaryAction.START_NEW_TASK
    }
    val expected = when {
        runStatus == AgentRunStatus.WAITING_USER -> expectedAction.ifBlank { "Ответьте на вопрос" }
        runStatus == AgentRunStatus.WAITING_APPROVAL -> expectedAction.ifBlank { "Утвердите план или попросите изменения" }
        runStatus == AgentRunStatus.STALE_PAUSED -> expectedAction.ifBlank { "Правила изменились. Требуется новый план" }
        // A refusal is rendered only from the typed safe payload. Do not invent a generic
        // explanation: that would conceal the rule metadata the user needs to inspect.
        runStatus == AgentRunStatus.REFUSED -> refusal?.toExactRefusalMessage().orEmpty()
        else -> expectedAction.ifBlank { "Подождите" }
    }
    val actionDescription = when (action) {
        AgentPrimaryAction.RETRY -> "Повторить шаг"
        AgentPrimaryAction.START_NEW_TASK -> "Начать новую задачу"
        AgentPrimaryAction.APPROVE_PLAN -> "Утвердить текущий план"
        AgentPrimaryAction.CONTINUE_WITH_CURRENT_RULES -> "Продолжить с текущими правилами"
        AgentPrimaryAction.OPEN_INVARIANTS -> "Открыть инварианты"
        AgentPrimaryAction.NONE -> null
    }
    return AgentUiState(
        phaseLabel = phase,
        stepLabel = step,
        expectedAction = expected,
        primaryAction = action,
        actionContentDescription = actionDescription,
    )
}

data class ChatState(
    val id: String,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val taskMemory: TaskMemory = TaskMemory(id),
    val taskDraft: TaskMemoryDraft = TaskMemoryDraft(),
    val taskEditorOpen: Boolean = false,
    val checkpoint: AgentCheckpoint? = null,
    val recoveryAvailable: Boolean = false,
    /** A persisted chat keeps its canonical snapshot visible until the user chooses its recovery draft. */
    val recoveryChoiceRequired: Boolean = false,
    val saveDialog: Boolean = false,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val planChangeDialog: Boolean = false,
    val planChangeComment: String = "",
    /** Presentation-only mirror of [AgentRunResult.refusal]; never contains a rule statement. */
    val refusal: SafeInvariantRefusal? = null,
) {
    val safeRefusal: SafeInvariantRefusal? get() = refusal ?: checkpoint?.refusal
    val agentUi: AgentUiState get() = checkpoint.toUiState(safeRefusal)
    val sending: Boolean get() = checkpoint?.runStatus == AgentRunStatus.ACTIVE && checkpoint.inFlight
    /** FAILED is intentionally terminal for the composer: retry or an explicit new task is required. */
    val composerEditable: Boolean get() =
        !loading && !loadFailed && !saving && !recoveryChoiceRequired && when (checkpoint?.runStatus) {
            null, AgentRunStatus.WAITING_USER, AgentRunStatus.COMPLETED, AgentRunStatus.TERMINATED -> true
            AgentRunStatus.ACTIVE -> !checkpoint.inFlight
            AgentRunStatus.WAITING_APPROVAL,
            AgentRunStatus.STALE_PAUSED,
            AgentRunStatus.FAILED,
            AgentRunStatus.REFUSED -> false
        }
    /** Task context is immutable while the current run has not completed. */
    val taskMemoryEditable: Boolean get() = checkpoint == null || checkpoint.runStatus == AgentRunStatus.COMPLETED || checkpoint.runStatus == AgentRunStatus.TERMINATED
}
sealed interface ChatIntent {
    data object Load : ChatIntent
    data class ChangeDraft(val value: String) : ChatIntent
    data object Send : ChatIntent
    data object Retry : ChatIntent
    data object ApprovePlan : ChatIntent
    data object OpenPlanChanges : ChatIntent
    data class ChangePlanComment(val value: String) : ChatIntent
    data object SubmitPlanChanges : ChatIntent
    data object ClosePlanChanges : ChatIntent
    data object ContinueWithCurrentRules : ChatIntent
    data object OpenInvariants : ChatIntent
    data object StartNewTask : ChatIntent
    data object ContinueRecovery : ChatIntent
    data object DiscardRecovery : ChatIntent
    data object OpenTaskEditor : ChatIntent
    data object CloseTaskEditor : ChatIntent
    data class ChangeTaskGoal(val value: String) : ChatIntent
    data class ChangeTaskConstraints(val value: String) : ChatIntent
    data class ChangeTaskResult(val value: String) : ChatIntent
    data class ChangeTaskDecisions(val value: String) : ChatIntent
    data object ApplyTaskMemory : ChatIntent
    data object RequestExit : ChatIntent
    data object ConfirmSave : ChatIntent
    data object Discard : ChatIntent
    data object CloseDialog : ChatIntent
}
sealed interface ChatEffect { data object TechnicalError : ChatEffect; data object NavigateHome : ChatEffect; data object OpenInvariants : ChatEffect }
