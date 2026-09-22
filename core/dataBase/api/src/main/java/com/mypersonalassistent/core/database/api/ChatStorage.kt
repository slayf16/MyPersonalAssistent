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
sealed interface StorageResult { data object Success : StorageResult; data object Failure : StorageResult }
