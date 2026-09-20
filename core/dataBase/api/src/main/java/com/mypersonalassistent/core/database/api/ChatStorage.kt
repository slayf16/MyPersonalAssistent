package com.mypersonalassistent.core.database.api

import kotlinx.coroutines.flow.Flow

data class StoredChatSummary(val id: String, val title: String, val updatedAt: Long)
data class StoredChat(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val contextJson: String)
interface ChatStorage {
    fun observeSummaries(): Flow<List<StoredChatSummary>>
    suspend fun read(id: String): StoredChat?
    suspend fun upsert(chat: StoredChat): StorageResult
}
sealed interface StorageResult { data object Success : StorageResult; data object Failure : StorageResult }
