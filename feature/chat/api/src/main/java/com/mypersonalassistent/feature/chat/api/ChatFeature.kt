package com.mypersonalassistent.feature.chat.api

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.memory.api.TaskMemory

data class TaskMemoryDraft(
    val goal: String = "",
    val constraints: String = "",
    val desiredResult: String = "",
    val decisions: String = "",
)
enum class AgentPrimaryAction { NONE, PAUSE, RESUME, RETRY, START_NEW_TASK }

/** Presentation-only view derived solely from the persisted workflow checkpoint. */
data class AgentUiState(
    val phaseLabel: String,
    val stepLabel: String,
    val expectedAction: String,
    val primaryAction: AgentPrimaryAction,
    val actionContentDescription: String? = null,
)

fun AgentCheckpoint?.toUiState(): AgentUiState {
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
        AgentRunStatus.ACTIVE -> AgentPrimaryAction.PAUSE
        AgentRunStatus.PAUSED -> AgentPrimaryAction.RESUME
        // The composer answers the question; Pause remains available as a separate safe action.
        AgentRunStatus.WAITING_USER -> AgentPrimaryAction.PAUSE
        AgentRunStatus.FAILED -> if (retryAllowed) AgentPrimaryAction.RETRY else AgentPrimaryAction.START_NEW_TASK
        AgentRunStatus.COMPLETED -> AgentPrimaryAction.START_NEW_TASK
    }
    val expected = when {
        runStatus == AgentRunStatus.WAITING_USER -> expectedAction.ifBlank { "Ответьте на вопрос" }
        else -> expectedAction.ifBlank { "Подождите" }
    }
    val actionDescription = when (action) {
        AgentPrimaryAction.PAUSE -> "Поставить задачу на паузу"
        AgentPrimaryAction.RESUME -> "Продолжить задачу"
        AgentPrimaryAction.RETRY -> "Повторить шаг"
        AgentPrimaryAction.START_NEW_TASK -> "Начать новую задачу"
        AgentPrimaryAction.NONE -> null
    }
    return AgentUiState(phase, step, expected, action, actionDescription)
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
) {
    val agentUi: AgentUiState get() = checkpoint.toUiState()
    val sending: Boolean get() = checkpoint?.runStatus == AgentRunStatus.ACTIVE
    /** FAILED is intentionally terminal for the composer: retry or an explicit new task is required. */
    val composerEditable: Boolean get() =
        !loading && !loadFailed && !saving && !recoveryChoiceRequired && checkpoint?.runStatus !in setOf(
            AgentRunStatus.ACTIVE,
            AgentRunStatus.PAUSED,
            AgentRunStatus.FAILED,
        )
    /** Task context is immutable while the current run has not completed. */
    val taskMemoryEditable: Boolean get() = checkpoint == null || checkpoint.runStatus == AgentRunStatus.COMPLETED
}
sealed interface ChatIntent {
    data object Load : ChatIntent
    data class ChangeDraft(val value: String) : ChatIntent
    data object Send : ChatIntent
    data object Pause : ChatIntent
    data object Resume : ChatIntent
    data object Retry : ChatIntent
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
sealed interface ChatEffect { data object TechnicalError : ChatEffect; data object NavigateHome : ChatEffect }
