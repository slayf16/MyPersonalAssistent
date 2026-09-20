package com.mypersonalassistent.core.credentials.api

interface CredentialRepository {
    suspend fun readApiKey(): String?
    suspend fun saveApiKey(value: String): CredentialWriteResult
}

sealed interface CredentialWriteResult { data object Success : CredentialWriteResult; data object Failure : CredentialWriteResult }
