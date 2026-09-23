package com.mypersonalassistent.feature.home.api

import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.AgentRecoverySummary
data class HomeState(val isLoading: Boolean = true, val error: Boolean = false, val chats: List<ChatSummary> = emptyList(), val recovery: List<AgentRecoverySummary> = emptyList())
sealed interface HomeIntent { data object Retry : HomeIntent; data object NewChat : HomeIntent; data object EditKey : HomeIntent; data object EditProfile : HomeIntent; data object OpenInvariants : HomeIntent; data class Open(val id: String) : HomeIntent; data class ContinueRecovery(val id: String) : HomeIntent; data class DiscardRecovery(val id: String) : HomeIntent }
sealed interface HomeEffect { data object NewChat : HomeEffect; data object EditKey : HomeEffect; data object EditProfile : HomeEffect; data object OpenInvariants : HomeEffect; data class Open(val id: String) : HomeEffect; data object TechnicalError : HomeEffect }
