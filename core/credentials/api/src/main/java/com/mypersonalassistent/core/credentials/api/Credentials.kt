package com.mypersonalassistent.core.credentials.api

interface CredentialRepository {
    suspend fun readApiKey(): String?
    suspend fun saveApiKey(value: String): CredentialWriteResult
}

sealed interface CredentialWriteResult { data object Success : CredentialWriteResult; data object Failure : CredentialWriteResult }

/** Server-scoped MCP credentials.  Metadata is deliberately kept out of this vault. */
data class McpSecrets(val token: String? = null, val apiKey: String? = null)
interface McpSecretRepository {
    suspend fun read(serverId: String): McpSecrets?
    suspend fun save(serverId: String, secrets: McpSecrets): CredentialWriteResult
    suspend fun delete(serverId: String): CredentialWriteResult
    suspend fun stage(serverId: String, secrets: McpSecrets): CredentialWriteResult = save(serverId, secrets)
    suspend fun activateStage(serverId: String): CredentialWriteResult = CredentialWriteResult.Success
    suspend fun discardStage(serverId: String): CredentialWriteResult = CredentialWriteResult.Success
    suspend fun hasStage(serverId: String): Boolean = false
    /** Durable staged server IDs, used to compensate stages whose Room intent never committed. */
    suspend fun stagedServerIds(): Set<String> = emptySet()
    /** Retries durable orphan cleanup left by a metadata-first server deletion. */
    suspend fun retryPendingDeletes(): CredentialWriteResult = CredentialWriteResult.Success
}
