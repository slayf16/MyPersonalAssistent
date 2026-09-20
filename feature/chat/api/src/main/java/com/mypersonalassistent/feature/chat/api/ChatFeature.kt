package com.mypersonalassistent.feature.chat.api

import com.mypersonalassistent.core.history.api.ChatMessage

data class ChatState(val id: String, val messages: List<ChatMessage> = emptyList(), val draft: String = "", val sending: Boolean = false, val saveDialog: Boolean = false, val saving: Boolean = false, val loading: Boolean = false, val loadFailed: Boolean = false)
sealed interface ChatIntent { data object Load : ChatIntent; data class ChangeDraft(val value: String) : ChatIntent; data object Send : ChatIntent; data object RequestExit : ChatIntent; data object ConfirmSave : ChatIntent; data object Discard : ChatIntent; data object CloseDialog : ChatIntent }
sealed interface ChatEffect { data object TechnicalError : ChatEffect; data object NavigateHome : ChatEffect }
