package com.mypersonalassistent.feature.chat.api

import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.memory.api.TaskMemory

data class TaskMemoryDraft(
    val goal: String = "",
    val constraints: String = "",
    val desiredResult: String = "",
    val decisions: String = "",
)
data class ChatState(
    val id: String,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val taskMemory: TaskMemory = TaskMemory(id),
    val taskDraft: TaskMemoryDraft = TaskMemoryDraft(),
    val taskEditorOpen: Boolean = false,
    val sending: Boolean = false,
    val saveDialog: Boolean = false,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
)
sealed interface ChatIntent {
    data object Load : ChatIntent
    data class ChangeDraft(val value: String) : ChatIntent
    data object Send : ChatIntent
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
