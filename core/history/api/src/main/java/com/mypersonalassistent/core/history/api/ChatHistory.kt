package com.mypersonalassistent.core.history.api

import com.mypersonalassistent.core.memory.api.TaskMemory
import kotlinx.coroutines.flow.Flow

enum class MessageRole { USER, ASSISTANT }
enum class DeliveryState { COMPLETE, INTERRUPTED }
data class ChatMessage(val id: String, val role: MessageRole, val content: String, val deliveryState: DeliveryState = DeliveryState.COMPLETE, val finishReason: String? = null)
data class ChatSnapshot(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val messages: List<ChatMessage>)
class HistoryCorruptionException : IllegalStateException("Stored chat cannot be decoded")
data class ChatSummary(val id: String, val title: String, val updatedAt: Long)
interface HistoryRepository {
    fun observeSummaries(): Flow<List<ChatSummary>>
    suspend fun read(id: String): ChatSnapshot?
    suspend fun save(snapshot: ChatSnapshot): Boolean
    /** Saves the chat and its task context atomically. */
    suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean
}
