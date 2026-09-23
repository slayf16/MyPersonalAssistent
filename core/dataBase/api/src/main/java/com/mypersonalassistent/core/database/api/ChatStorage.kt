package com.mypersonalassistent.core.database.api

import kotlinx.coroutines.flow.Flow

data class StoredChatSummary(val id: String, val title: String, val updatedAt: Long)
data class StoredChat(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val contextJson: String)
data class StoredProfile(
    val onboardingStatus: String,
    val preferredName: String?,
    val language: String,
    val tone: String,
    val detailLevel: String,
    val customInstructions: String,
    val updatedAt: Long,
    val customLanguage: String = "",
    val customTone: String = "",
    val customDetailLevel: String = "",
)
data class StoredTaskMemory(
    val chatId: String,
    val goal: String,
    val constraintsJson: String,
    val desiredResult: String,
    val decisionsJson: String,
    val updatedAt: Long,
)
interface ChatStorage {
    fun observeSummaries(): Flow<List<StoredChatSummary>>
    suspend fun read(id: String): StoredChat?
    suspend fun upsert(chat: StoredChat): StorageResult
    /**
     * Persists the chat and its task context as a single operation. Implementations must not
     * report success after persisting only one side of the bundle.
     */
    suspend fun upsertWithTaskMemory(chat: StoredChat, taskMemory: StoredTaskMemory?): StorageResult
}
interface MemoryStorage {
    suspend fun readProfile(): StoredProfile?
    suspend fun upsertProfile(profile: StoredProfile): StorageResult
    suspend fun readTaskMemory(chatId: String): StoredTaskMemory?
}
data class StoredAgentCheckpoint(
    val chatId: String,
    val checkpointJson: String,
    val updatedAt: Long,
)
data class StoredAgentRecovery(
    val chatId: String,
    val isCanonicalChat: Boolean,
    val title: String,
    val createdAt: Long,
    val chatUpdatedAt: Long,
    val contextJson: String,
    val taskGoal: String,
    val taskConstraintsJson: String,
    val taskDesiredResult: String,
    val taskDecisionsJson: String,
    val taskUpdatedAt: Long,
    val checkpointJson: String,
    val updatedAt: Long,
)
data class StoredRecoverySummary(val chatId: String, val isCanonicalChat: Boolean, val updatedAt: Long)
interface AgentStorage {
    fun observeRecoverySummaries(): Flow<List<StoredRecoverySummary>>
    suspend fun readAgentCheckpoint(chatId: String): StoredAgentCheckpoint?
    suspend fun readAgentRecovery(chatId: String): StoredAgentRecovery?
    /** Writes only the non-canonical recovery draft. */
    suspend fun writeAgentRecovery(recovery: StoredAgentRecovery): StorageResult
    /** Atomically promotes chat, task memory and checkpoint, then removes the recovery draft. */
    suspend fun promoteAgentRecovery(recovery: StoredAgentRecovery): StorageResult
    suspend fun discardAgentRecovery(chatId: String): StorageResult
}
sealed interface StorageResult { data object Success : StorageResult; data object Failure : StorageResult }
