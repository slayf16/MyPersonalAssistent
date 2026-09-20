package com.mypersonalassistent.feature.credentials.api

data class CredentialsState(val value: String = "", val isSaving: Boolean = false)
sealed interface CredentialsIntent { data object Load : CredentialsIntent; data class Change(val value: String) : CredentialsIntent; data object Save : CredentialsIntent }
sealed interface CredentialsEffect { data object ExistingKey : CredentialsEffect; data object Saved : CredentialsEffect; data object TechnicalError : CredentialsEffect }
