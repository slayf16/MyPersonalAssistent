package com.mypersonalassistent.core.database.api

import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantOperation
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.RuleRevision
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

/** Storage-only rows. They intentionally contain no Room entity or DAO type. */
data class StoredInvariantRule(
    val id: InvariantRuleId, val title: String, val category: InvariantCategory, val statement: String,
    val enabled: Boolean, val revision: RuleRevision, val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null,
)
data class StoredInvariantAudit(
    val eventId: String, val ruleId: InvariantRuleId, val operation: InvariantOperation,
    val oldRevision: RuleRevision?, val newRevision: RuleRevision?, val oldDigest: String?, val newDigest: String?,
    val collectionRevision: CollectionRevision, val createdAt: Long,
)
data class StoredInvariantSnapshot(
    val id: String, val collectionRevision: CollectionRevision, val payload: String, val contentDigest: String,
    val schemaVersion: Int, val createdAt: Long,
)
data class StoredInvariantMutation(
    val operation: InvariantOperation, val ruleId: InvariantRuleId, val title: String?, val category: InvariantCategory?,
    val statement: String?, val enabled: Boolean?, val expectedCollectionRevision: CollectionRevision,
    val expectedRuleRevision: RuleRevision?, val createdAt: Long,
)
sealed interface StoredInvariantCommitResult {
    data class Committed(val rule: StoredInvariantRule?, val collectionRevision: CollectionRevision, val affectedRunIds: Set<String>) : StoredInvariantCommitResult
    data class Rejected(val code: String, val currentCollectionRevision: CollectionRevision, val currentRuleRevision: RuleRevision? = null, val duplicateRule: StoredInvariantRule? = null) : StoredInvariantCommitResult
}
data class StoredAgentRunIndex(
    val chatId: String, val runId: String, val collectionRevision: CollectionRevision, val isNonterminal: Boolean,
    val isActive: Boolean, val isStale: Boolean, val staleTarget: String?, val updatedAt: Long,
)
interface InvariantStorage {
    fun observeInvariantRules(): Flow<List<StoredInvariantRule>>
    suspend fun readInvariantRule(id: InvariantRuleId): StoredInvariantRule?
    suspend fun readInvariantRulesIncludingDeleted(): List<StoredInvariantRule>
    suspend fun invariantCollectionRevision(): CollectionRevision
    suspend fun countAffectedNonterminalRuns(collectionRevision: CollectionRevision): Int
    suspend fun commitInvariantMutation(mutation: StoredInvariantMutation): StoredInvariantCommitResult
    suspend fun saveInvariantSnapshot(snapshot: StoredInvariantSnapshot): StorageResult
    suspend fun readInvariantSnapshot(id: String): StoredInvariantSnapshot?
    suspend fun upsertAgentRunIndex(index: StoredAgentRunIndex): StorageResult
    suspend fun readAgentRunIndex(chatId: String): StoredAgentRunIndex?
}
