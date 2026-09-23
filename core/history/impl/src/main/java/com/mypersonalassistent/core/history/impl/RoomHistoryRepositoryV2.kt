package com.mypersonalassistent.core.history.impl

import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.AgentStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChat
import com.mypersonalassistent.core.database.api.StoredTaskMemory
import com.mypersonalassistent.core.database.api.StoredAgentRecovery
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.DeliveryState
import com.mypersonalassistent.core.history.api.HistoryCorruptionException
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.AgentRecoverySummary
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.memory.api.TaskMemory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RoomHistoryRepository(
    private val storage: ChatStorage,
    private val agentStorage: AgentStorage,
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryRepository, AgentRecoveryRepository {
    override fun observeSummaries(): Flow<List<ChatSummary>> = storage.observeSummaries().map { rows ->
        rows.map { ChatSummary(it.id, it.title, it.updatedAt) }
    }

    override suspend fun read(id: String): ChatSnapshot? = withContext(dispatcher) {
        storage.read(id)?.let(::decode)
    }

    override suspend fun save(snapshot: ChatSnapshot): Boolean = withContext(dispatcher) {
        storage.upsert(snapshot.toStored()).let { it is StorageResult.Success }
    }

    override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory): Boolean = withContext(dispatcher) {
        storage.upsertWithTaskMemory(
            snapshot.toStored(),
            taskMemory.takeUnless { it.isEmpty }?.let {
                StoredTaskMemory(
                    chatId = snapshot.id,
                    goal = it.goal.trim(),
                    constraintsJson = json.encodeToString(it.constraints.map { value -> value.trim() }.filter(String::isNotEmpty)),
                    desiredResult = it.desiredResult.trim(),
                    decisionsJson = json.encodeToString(it.decisions.map { value -> value.trim() }.filter(String::isNotEmpty)),
                    updatedAt = it.updatedAt,
                )
            },
        ) is StorageResult.Success
    }

    override fun observeRecovery(): Flow<List<AgentRecoverySummary>> =
        agentStorage.observeRecoverySummaries().map { rows ->
            rows.map { AgentRecoverySummary(it.chatId, it.isCanonicalChat, it.updatedAt) }
        }

    override suspend fun readCheckpoint(chatId: String): String? = withContext(dispatcher) {
        agentStorage.readAgentCheckpoint(chatId)?.checkpointJson
    }

    override suspend fun readRecovery(chatId: String): AgentRecovery? = withContext(dispatcher) {
        agentStorage.readAgentRecovery(chatId)?.toRecovery()
    }

    override suspend fun writeRecovery(recovery: AgentRecovery): Boolean = withContext(dispatcher) {
        agentStorage.writeAgentRecovery(recovery.toStored()) is StorageResult.Success
    }

    override suspend fun promoteRecovery(recovery: AgentRecovery): Boolean = withContext(dispatcher) {
        agentStorage.promoteAgentRecovery(recovery.toStored()) is StorageResult.Success
    }

    override suspend fun discardRecovery(chatId: String): Boolean = withContext(dispatcher) {
        agentStorage.discardAgentRecovery(chatId) is StorageResult.Success
    }

    private fun ChatSnapshot.toStored() = StoredChat(
        id,
        title,
        createdAt,
        updatedAt,
        json.encodeToString(PersistedContext.from(this)),
    )

    private fun AgentRecovery.toStored() = StoredAgentRecovery(
        chatId = snapshot.id,
        isCanonicalChat = isCanonicalChat,
        title = snapshot.title,
        createdAt = snapshot.createdAt,
        chatUpdatedAt = snapshot.updatedAt,
        contextJson = json.encodeToString(PersistedContext.from(snapshot)),
        taskGoal = taskMemory.goal.trim(),
        taskConstraintsJson = json.encodeToString(taskMemory.constraints.map(String::trim).filter(String::isNotEmpty)),
        taskDesiredResult = taskMemory.desiredResult.trim(),
        taskDecisionsJson = json.encodeToString(taskMemory.decisions.map(String::trim).filter(String::isNotEmpty)),
        taskUpdatedAt = taskMemory.updatedAt,
        checkpointJson = checkpointJson,
        updatedAt = updatedAt,
    )

    private fun StoredAgentRecovery.toRecovery(): AgentRecovery = try {
        val stored = StoredChat(chatId, title, createdAt, chatUpdatedAt, contextJson)
        AgentRecovery(
            snapshot = decode(stored),
            taskMemory = TaskMemory(
                chatId = chatId,
                goal = taskGoal,
                constraints = json.decodeFromString(taskConstraintsJson),
                desiredResult = taskDesiredResult,
                decisions = json.decodeFromString(taskDecisionsJson),
                updatedAt = taskUpdatedAt,
            ),
            checkpointJson = checkpointJson,
            isCanonicalChat = isCanonicalChat,
            updatedAt = updatedAt,
        )
    } catch (_: Throwable) {
        throw HistoryCorruptionException()
    }

    private fun decode(stored: StoredChat): ChatSnapshot = try {
        json.decodeFromString<PersistedContext>(stored.contextJson).toSnapshot(stored)
    } catch (_: Throwable) {
        throw HistoryCorruptionException()
    }
}

@Serializable
private data class PersistedContext(
    val schemaVersion: Int = 1,
    val messages: List<PersistedMessage>,
) {
    fun toSnapshot(chat: StoredChat): ChatSnapshot {
        require(schemaVersion == 1) { "Unsupported history schema" }
        return ChatSnapshot(
            chat.id,
            chat.title,
            chat.createdAt,
            chat.updatedAt,
            messages.map {
                ChatMessage(
                    it.id,
                    MessageRole.valueOf(it.role),
                    it.content,
                    DeliveryState.valueOf(it.deliveryState),
                    it.finishReason,
                )
            },
        )
    }

    companion object {
        fun from(snapshot: ChatSnapshot) = PersistedContext(
            messages = snapshot.messages.map {
                PersistedMessage(it.id, it.role.name, it.content, it.deliveryState.name, it.finishReason)
            }
        )
    }
}

@Serializable
private data class PersistedMessage(
    val id: String,
    val role: String,
    val content: String,
    val deliveryState: String,
    val finishReason: String?,
)
