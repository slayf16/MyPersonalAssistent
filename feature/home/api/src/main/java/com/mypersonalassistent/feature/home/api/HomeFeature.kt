package com.mypersonalassistent.feature.home.api

import com.mypersonalassistent.core.history.api.ChatSummary
data class HomeState(val isLoading: Boolean = true, val error: Boolean = false, val chats: List<ChatSummary> = emptyList())
sealed interface HomeIntent { data object Retry : HomeIntent; data object NewChat : HomeIntent; data object EditKey : HomeIntent; data class Open(val id: String) : HomeIntent }
sealed interface HomeEffect { data object NewChat : HomeEffect; data object EditKey : HomeEffect; data class Open(val id: String) : HomeEffect; data object TechnicalError : HomeEffect }
