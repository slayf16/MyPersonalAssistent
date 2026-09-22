package com.mypersonalassistent.feature.profile.api

import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone

data class ProfileState(
    val firstRun: Boolean,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val preferredName: String = "",
    val language: ResponseLanguage = ResponseLanguage.AUTO,
    val tone: ResponseTone = ResponseTone.NEUTRAL,
    val detailLevel: ResponseDetail = ResponseDetail.BALANCED,
    val customLanguage: String = "",
    val customTone: String = "",
    val customDetailLevel: String = "",
    val languageError: Boolean = false,
    val toneError: Boolean = false,
    val detailError: Boolean = false,
    val customInstructions: String = "",
)

sealed interface ProfileIntent {
    data object Load : ProfileIntent
    data class ChangeName(val value: String) : ProfileIntent
    data class SelectLanguage(val value: ResponseLanguage) : ProfileIntent
    data class SelectTone(val value: ResponseTone) : ProfileIntent
    data class SelectDetail(val value: ResponseDetail) : ProfileIntent
    data class ChangeCustomLanguage(val value: String) : ProfileIntent
    data class ChangeCustomTone(val value: String) : ProfileIntent
    data class ChangeCustomDetail(val value: String) : ProfileIntent
    data class ChangeInstructions(val value: String) : ProfileIntent
    data object Save : ProfileIntent
    data object Skip : ProfileIntent
    data object Clear : ProfileIntent
    data object Back : ProfileIntent
}

sealed interface ProfileEffect {
    data object Done : ProfileEffect
    data object TechnicalError : ProfileEffect
}
