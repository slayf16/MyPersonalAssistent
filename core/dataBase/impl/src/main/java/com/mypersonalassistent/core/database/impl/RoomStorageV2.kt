package com.mypersonalassistent.core.database.impl

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.AgentStorage
import com.mypersonalassistent.core.database.api.MemoryStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChat
import com.mypersonalassistent.core.database.api.StoredChatSummary
import com.mypersonalassistent.core.database.api.StoredProfile
import com.mypersonalassistent.core.database.api.StoredTaskMemory
import com.mypersonalassistent.core.database.api.StoredAgentCheckpoint
import com.mypersonalassistent.core.database.api.StoredAgentRecovery
import com.mypersonalassistent.core.database.api.StoredRecoverySummary
import com.mypersonalassistent.core.database.api.InvariantStorage
import com.mypersonalassistent.core.database.api.StoredInvariantRule
import com.mypersonalassistent.core.database.api.StoredInvariantAudit
import com.mypersonalassistent.core.database.api.StoredInvariantSnapshot
import com.mypersonalassistent.core.database.api.StoredInvariantMutation
import com.mypersonalassistent.core.database.api.StoredInvariantCommitResult
import com.mypersonalassistent.core.database.api.StoredAgentRunIndex
import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantOperation
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.RuleRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val contextJson: String,
)

@Entity(tableName = "agent_profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = PROFILE_ID,
    val onboardingStatus: String,
    val preferredName: String?,
    val language: String,
    val tone: String,
    val detailLevel: String,
    val customInstructions: String,
    val updatedAt: Long,
    val customLanguage: String,
    val customTone: String,
    val customDetailLevel: String,
)

@Entity(
    tableName = "task_memory",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chatId")],
)
data class TaskMemoryEntity(
    @PrimaryKey val chatId: String,
    val goal: String,
    val constraintsJson: String,
    val desiredResult: String,
    val decisionsJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_checkpoints",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chatId")],
)
data class AgentCheckpointEntity(
    @PrimaryKey val chatId: String,
    val checkpointJson: String,
    val updatedAt: Long,
)

/** No FK: a recovery draft also represents a newly created, not-yet-canonical chat. */
@Entity(tableName = "agent_recovery", indices = [Index("updatedAt")])
data class AgentRecoveryEntity(
    @PrimaryKey val chatId: String,
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

@Entity(tableName = "invariant_collection")
data class InvariantCollectionEntity(@PrimaryKey val id: Int = 1, val revision: Long)

@Entity(tableName = "invariant_rules", indices = [Index(value = ["deletedAt", "enabled", "updatedAt", "id"]), Index(value = ["category", "statement", "deletedAt"])])
data class InvariantRuleEntity(
    @PrimaryKey val id: String, val title: String, val category: String, val statement: String,
    val enabled: Boolean, val revision: Long, val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null,
)

@Entity(tableName = "invariant_audit", indices = [Index("ruleId"), Index("collectionRevision")])
data class InvariantAuditEntity(
    @PrimaryKey val eventId: String, val ruleId: String, val operation: String, val oldRevision: Long?, val newRevision: Long?,
    val oldDigest: String?, val newDigest: String?, val collectionRevision: Long, val createdAt: Long,
)

@Entity(tableName = "invariant_snapshots", indices = [Index("collectionRevision"), Index("contentDigest")])
data class InvariantSnapshotEntity(
    @PrimaryKey val id: String, val collectionRevision: Long, val payload: String, val contentDigest: String,
    val schemaVersion: Int, val createdAt: Long,
)

@Entity(tableName = "agent_run_index", indices = [Index(value = ["isNonterminal", "collectionRevision"]), Index("runId")])
data class AgentRunIndexEntity(
    @PrimaryKey val chatId: String, val runId: String, val collectionRevision: Long, val isNonterminal: Boolean,
    val isActive: Boolean, val isStale: Boolean, val staleTarget: String?, val updatedAt: Long,
)

@Dao
internal interface ChatDao {
    @Query("SELECT id, title, updatedAt FROM chats ORDER BY updatedAt DESC, id ASC")
    fun summaries(): Flow<List<ChatSummaryRow>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun read(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)
}

@Dao
internal interface MemoryDao {
    @Query("SELECT * FROM agent_profile WHERE id = 1")
    suspend fun readProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    @Query("SELECT * FROM task_memory WHERE chatId = :chatId")
    suspend fun readTaskMemory(chatId: String): TaskMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskMemory(memory: TaskMemoryEntity)

    @Query("DELETE FROM task_memory WHERE chatId = :chatId")
    suspend fun deleteTaskMemory(chatId: String)
}

internal data class RecoverySummaryRow(val chatId: String, val isCanonicalChat: Boolean, val updatedAt: Long)

@Dao
internal interface AgentDao {
    @Query("SELECT * FROM agent_checkpoints WHERE chatId = :chatId")
    suspend fun readCheckpoint(chatId: String): AgentCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: AgentCheckpointEntity)

    @Query("SELECT * FROM agent_recovery WHERE chatId = :chatId")
    suspend fun readRecovery(chatId: String): AgentRecoveryEntity?

    @Query("SELECT chatId, isCanonicalChat, updatedAt FROM agent_recovery ORDER BY updatedAt DESC, chatId ASC")
    fun recoverySummaries(): Flow<List<RecoverySummaryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecovery(recovery: AgentRecoveryEntity)

    @Query("DELETE FROM agent_recovery WHERE chatId = :chatId")
    suspend fun deleteRecovery(chatId: String)
}

@Dao
internal interface InvariantDao {
    @Query("SELECT * FROM invariant_rules WHERE deletedAt IS NULL ORDER BY enabled DESC, updatedAt DESC, id ASC")
    fun observeCurrent(): Flow<List<InvariantRuleEntity>>

    @Query("SELECT * FROM invariant_rules WHERE id = :id")
    suspend fun readAny(id: String): InvariantRuleEntity?

    @Query("SELECT * FROM invariant_rules")
    suspend fun readAll(): List<InvariantRuleEntity>

    @Query("SELECT revision FROM invariant_collection WHERE id = 1")
    suspend fun collectionRevision(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initializeCollection(row: InvariantCollectionEntity)

    @Query("UPDATE invariant_collection SET revision = :revision WHERE id = 1")
    suspend fun updateCollectionRevision(revision: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: InvariantRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateRule(rule: InvariantRuleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAudit(audit: InvariantAuditEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: InvariantSnapshotEntity)

    @Query("SELECT * FROM invariant_snapshots WHERE id = :id")
    suspend fun readSnapshot(id: String): InvariantSnapshotEntity?

    @Query("SELECT chatId FROM agent_run_index WHERE isNonterminal = 1 AND collectionRevision = :revision")
    suspend fun affectedRunIds(revision: Long): List<String>

    @Query("SELECT COUNT(*) FROM agent_run_index WHERE isNonterminal = 1 AND collectionRevision = :revision")
    suspend fun countAffected(revision: Long): Int

    @Query("UPDATE agent_run_index SET isStale = 1, isActive = 0, staleTarget = CASE WHEN isActive = 1 THEN 'ACTIVE' ELSE staleTarget END WHERE isNonterminal = 1 AND collectionRevision = :revision")
    suspend fun markAffectedStale(revision: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunIndex(index: AgentRunIndexEntity)

    @Query("SELECT * FROM agent_run_index WHERE chatId = :chatId")
    suspend fun readRunIndex(chatId: String): AgentRunIndexEntity?
}

internal data class ChatSummaryRow(val id: String, val title: String, val updatedAt: Long)

@Database(
    entities = [ChatEntity::class, ProfileEntity::class, TaskMemoryEntity::class, AgentCheckpointEntity::class, AgentRecoveryEntity::class, InvariantCollectionEntity::class, InvariantRuleEntity::class, InvariantAuditEntity::class, InvariantSnapshotEntity::class, AgentRunIndexEntity::class],
    version = 5,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentDao(): AgentDao
    abstract fun invariantDao(): InvariantDao
}

class RoomChatStorage private constructor(private val database: AppDatabase) : ChatStorage, MemoryStorage, AgentStorage, InvariantStorage {
    override fun observeSummaries(): Flow<List<StoredChatSummary>> =
        database.chatDao().summaries().map { rows ->
            rows.map { StoredChatSummary(it.id, it.title, it.updatedAt) }
        }

    override suspend fun read(id: String): StoredChat? = database.chatDao().read(id)?.toStored()

    override suspend fun upsert(chat: StoredChat): StorageResult = runStorage {
        database.chatDao().upsert(chat.toEntity())
    }

    override suspend fun upsertWithTaskMemory(
        chat: StoredChat,
        taskMemory: StoredTaskMemory?,
    ): StorageResult = runStorage {
        database.withTransaction {
            database.chatDao().upsert(chat.toEntity())
            if (taskMemory == null) {
                database.memoryDao().deleteTaskMemory(chat.id)
            } else {
                database.memoryDao().upsertTaskMemory(taskMemory.toEntity())
            }
        }
    }

    override suspend fun readProfile(): StoredProfile? =
        database.memoryDao().readProfile()?.toStored()

    override suspend fun upsertProfile(profile: StoredProfile): StorageResult = runStorage {
        database.memoryDao().upsertProfile(profile.toEntity())
    }

    override suspend fun readTaskMemory(chatId: String): StoredTaskMemory? =
        database.memoryDao().readTaskMemory(chatId)?.toStored()

    override fun observeRecoverySummaries(): Flow<List<StoredRecoverySummary>> =
        database.agentDao().recoverySummaries().map { rows ->
            rows.map { StoredRecoverySummary(it.chatId, it.isCanonicalChat, it.updatedAt) }
        }

    override suspend fun readAgentCheckpoint(chatId: String): StoredAgentCheckpoint? =
        database.agentDao().readCheckpoint(chatId)?.toStored()

    override suspend fun readAgentRecovery(chatId: String): StoredAgentRecovery? =
        database.agentDao().readRecovery(chatId)?.toStored()

    override suspend fun writeAgentRecovery(recovery: StoredAgentRecovery): StorageResult = runStorage {
        database.agentDao().upsertRecovery(recovery.toEntity())
    }

    override suspend fun promoteAgentRecovery(recovery: StoredAgentRecovery): StorageResult = runStorage {
        database.withTransaction {
            database.chatDao().upsert(ChatEntity(recovery.chatId, recovery.title, recovery.createdAt, recovery.chatUpdatedAt, recovery.contextJson))
            database.memoryDao().upsertTaskMemory(TaskMemoryEntity(
                recovery.chatId, recovery.taskGoal, recovery.taskConstraintsJson,
                recovery.taskDesiredResult, recovery.taskDecisionsJson, recovery.taskUpdatedAt,
            ))
            database.agentDao().upsertCheckpoint(AgentCheckpointEntity(recovery.chatId, recovery.checkpointJson, recovery.updatedAt))
            database.agentDao().deleteRecovery(recovery.chatId)
        }
    }

    override suspend fun discardAgentRecovery(chatId: String): StorageResult = runStorage {
        database.agentDao().deleteRecovery(chatId)
    }

    override fun observeInvariantRules(): Flow<List<StoredInvariantRule>> =
        database.invariantDao().observeCurrent().map { rows -> rows.map(InvariantRuleEntity::toStored) }

    override suspend fun readInvariantRule(id: InvariantRuleId): StoredInvariantRule? =
        database.invariantDao().readAny(id.value)?.takeIf { it.deletedAt == null }?.toStored()

    override suspend fun readInvariantRulesIncludingDeleted(): List<StoredInvariantRule> =
        database.invariantDao().readAll().map(InvariantRuleEntity::toStored)

    override suspend fun invariantCollectionRevision(): CollectionRevision =
        CollectionRevision(database.invariantDao().collectionRevision() ?: 0L)

    override suspend fun countAffectedNonterminalRuns(collectionRevision: CollectionRevision): Int =
        database.invariantDao().countAffected(collectionRevision.value)

    override suspend fun commitInvariantMutation(mutation: StoredInvariantMutation): StoredInvariantCommitResult = try {
        database.withTransaction {
            val dao = database.invariantDao()
            dao.initializeCollection(InvariantCollectionEntity(revision = 0))
            val current = dao.collectionRevision() ?: 0L
            if (current != mutation.expectedCollectionRevision.value) {
                return@withTransaction StoredInvariantCommitResult.Rejected("STALE_COLLECTION", CollectionRevision(current))
            }
            val old = dao.readAny(mutation.ruleId.value)
            if (mutation.operation != InvariantOperation.CREATE && (old == null || old.deletedAt != null)) {
                return@withTransaction StoredInvariantCommitResult.Rejected("NOT_FOUND", CollectionRevision(current))
            }
            val expectedRuleRevision = mutation.expectedRuleRevision
            if (expectedRuleRevision != null && old?.revision != expectedRuleRevision.value) {
                return@withTransaction StoredInvariantCommitResult.Rejected("STALE_RULE", CollectionRevision(current), old?.revision?.let(::RuleRevision))
            }
            val nextRevision = current + 1
            val next = when (mutation.operation) {
                InvariantOperation.CREATE -> InvariantRuleEntity(mutation.ruleId.value, requireNotNull(mutation.title), requireNotNull(mutation.category).name, requireNotNull(mutation.statement), requireNotNull(mutation.enabled), 1, mutation.createdAt, mutation.createdAt)
                InvariantOperation.EDIT -> old!!.copy(title = requireNotNull(mutation.title), category = requireNotNull(mutation.category).name, statement = requireNotNull(mutation.statement), enabled = requireNotNull(mutation.enabled), revision = old.revision + 1, updatedAt = mutation.createdAt)
                InvariantOperation.ENABLE, InvariantOperation.DISABLE -> old!!.copy(enabled = requireNotNull(mutation.enabled), revision = old.revision + 1, updatedAt = mutation.createdAt)
                InvariantOperation.DELETE -> old!!.copy(revision = old.revision + 1, updatedAt = mutation.createdAt, deletedAt = mutation.createdAt)
            }
            if (mutation.operation == InvariantOperation.CREATE) dao.insertRule(next) else dao.updateRule(next)
            dao.updateCollectionRevision(nextRevision)
            dao.insertAudit(InvariantAuditEntity(java.util.UUID.randomUUID().toString(), next.id, mutation.operation.name, old?.revision, next.revision, old?.digest(), next.digest(), nextRevision, mutation.createdAt))
            val affected = dao.affectedRunIds(current).toSet()
            dao.markAffectedStale(current)
            StoredInvariantCommitResult.Committed(next.toStored().takeIf { it.deletedAt == null }, CollectionRevision(nextRevision), affected)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        StoredInvariantCommitResult.Rejected("STORAGE", invariantCollectionRevision())
    }

    override suspend fun saveInvariantSnapshot(snapshot: StoredInvariantSnapshot): StorageResult = runStorage {
        database.invariantDao().insertSnapshot(snapshot.toEntity())
    }

    override suspend fun readInvariantSnapshot(id: String): StoredInvariantSnapshot? = database.invariantDao().readSnapshot(id)?.toStored()

    override suspend fun upsertAgentRunIndex(index: StoredAgentRunIndex): StorageResult = runStorage { database.invariantDao().upsertRunIndex(index.toEntity()) }
    override suspend fun readAgentRunIndex(chatId: String): StoredAgentRunIndex? = database.invariantDao().readRunIndex(chatId)?.toStored()

    internal fun closeForTesting() = database.close()

    private suspend fun runStorage(block: suspend () -> Unit): StorageResult = try {
        block()
        StorageResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        StorageResult.Failure
    }

    companion object {
        fun create(context: Context): RoomChatStorage = RoomChatStorage(
            buildDatabase(context, "my-personal-assistent.db")
        )

        internal fun createForTesting(context: Context, databaseName: String): RoomChatStorage =
            RoomChatStorage(buildDatabase(context, databaseName))

        private fun buildDatabase(context: Context, databaseName: String): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                databaseName,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}

private fun StoredChat.toEntity() = ChatEntity(id, title, createdAt, updatedAt, contextJson)
private fun ChatEntity.toStored() = StoredChat(id, title, createdAt, updatedAt, contextJson)
private fun StoredProfile.toEntity() = ProfileEntity(
    onboardingStatus = onboardingStatus,
    preferredName = preferredName,
    language = language,
    tone = tone,
    detailLevel = detailLevel,
    customInstructions = customInstructions,
    updatedAt = updatedAt,
    customLanguage = customLanguage,
    customTone = customTone,
    customDetailLevel = customDetailLevel,
)
private fun ProfileEntity.toStored() = StoredProfile(
    onboardingStatus,
    preferredName,
    language,
    tone,
    detailLevel,
    customInstructions,
    updatedAt,
    customLanguage,
    customTone,
    customDetailLevel,
)
private fun StoredTaskMemory.toEntity() = TaskMemoryEntity(
    chatId,
    goal,
    constraintsJson,
    desiredResult,
    decisionsJson,
    updatedAt,
)
private fun TaskMemoryEntity.toStored() = StoredTaskMemory(
    chatId,
    goal,
    constraintsJson,
    desiredResult,
    decisionsJson,
    updatedAt,
)
private fun AgentCheckpointEntity.toStored() = StoredAgentCheckpoint(chatId, checkpointJson, updatedAt)
private fun AgentRecoveryEntity.toStored() = StoredAgentRecovery(
    chatId, isCanonicalChat, title, createdAt, chatUpdatedAt, contextJson,
    taskGoal, taskConstraintsJson, taskDesiredResult, taskDecisionsJson,
    taskUpdatedAt, checkpointJson, updatedAt,
)
private fun StoredAgentRecovery.toEntity() = AgentRecoveryEntity(
    chatId, isCanonicalChat, title, createdAt, chatUpdatedAt, contextJson,
    taskGoal, taskConstraintsJson, taskDesiredResult, taskDecisionsJson,
    taskUpdatedAt, checkpointJson, updatedAt,
)
private fun InvariantRuleEntity.toStored() = StoredInvariantRule(InvariantRuleId(id), title, InvariantCategory.valueOf(category), statement, enabled, RuleRevision(revision), createdAt, updatedAt, deletedAt)
private fun StoredInvariantSnapshot.toEntity() = InvariantSnapshotEntity(id, collectionRevision.value, payload, contentDigest, schemaVersion, createdAt)
private fun InvariantSnapshotEntity.toStored() = StoredInvariantSnapshot(id, CollectionRevision(collectionRevision), payload, contentDigest, schemaVersion, createdAt)
private fun StoredAgentRunIndex.toEntity() = AgentRunIndexEntity(chatId, runId, collectionRevision.value, isNonterminal, isActive, isStale, staleTarget, updatedAt)
private fun AgentRunIndexEntity.toStored() = StoredAgentRunIndex(chatId, runId, CollectionRevision(collectionRevision), isNonterminal, isActive, isStale, staleTarget, updatedAt)
private fun InvariantRuleEntity.digest(): String = listOf(id, title, category, statement, enabled, revision, deletedAt).joinToString("|").sha256()
private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `agent_profile` (
                |`id` INTEGER NOT NULL,
                |`onboardingStatus` TEXT NOT NULL,
                |`preferredName` TEXT,
                |`language` TEXT NOT NULL,
                |`tone` TEXT NOT NULL,
                |`detailLevel` TEXT NOT NULL,
                |`customInstructions` TEXT NOT NULL,
                |`updatedAt` INTEGER NOT NULL,
                |PRIMARY KEY(`id`))""".trimMargin()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `task_memory` (
                |`chatId` TEXT NOT NULL,
                |`goal` TEXT NOT NULL,
                |`constraintsJson` TEXT NOT NULL,
                |`desiredResult` TEXT NOT NULL,
                |`decisionsJson` TEXT NOT NULL,
                |`updatedAt` INTEGER NOT NULL,
                |PRIMARY KEY(`chatId`),
                |FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimMargin()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_memory_chatId` ON `task_memory` (`chatId`)")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `agent_profile` ADD COLUMN `customLanguage` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `agent_profile` ADD COLUMN `customTone` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `agent_profile` ADD COLUMN `customDetailLevel` TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `agent_checkpoints` (`chatId` TEXT NOT NULL, `checkpointJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`), FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_checkpoints_chatId` ON `agent_checkpoints` (`chatId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `agent_recovery` (`chatId` TEXT NOT NULL, `isCanonicalChat` INTEGER NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `chatUpdatedAt` INTEGER NOT NULL, `contextJson` TEXT NOT NULL, `taskGoal` TEXT NOT NULL, `taskConstraintsJson` TEXT NOT NULL, `taskDesiredResult` TEXT NOT NULL, `taskDecisionsJson` TEXT NOT NULL, `taskUpdatedAt` INTEGER NOT NULL, `checkpointJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_updatedAt` ON `agent_recovery` (`updatedAt`)")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `invariant_collection` (`id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT OR IGNORE INTO `invariant_collection` (`id`, `revision`) VALUES (1, 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `invariant_rules` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `statement` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_rules_deletedAt_enabled_updatedAt_id` ON `invariant_rules` (`deletedAt`, `enabled`, `updatedAt`, `id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_rules_category_statement_deletedAt` ON `invariant_rules` (`category`, `statement`, `deletedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `invariant_audit` (`eventId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `operation` TEXT NOT NULL, `oldRevision` INTEGER, `newRevision` INTEGER, `oldDigest` TEXT, `newDigest` TEXT, `collectionRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_audit_ruleId` ON `invariant_audit` (`ruleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_audit_collectionRevision` ON `invariant_audit` (`collectionRevision`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `invariant_snapshots` (`id` TEXT NOT NULL, `collectionRevision` INTEGER NOT NULL, `payload` TEXT NOT NULL, `contentDigest` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_snapshots_collectionRevision` ON `invariant_snapshots` (`collectionRevision`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invariant_snapshots_contentDigest` ON `invariant_snapshots` (`contentDigest`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_run_index` (`chatId` TEXT NOT NULL, `runId` TEXT NOT NULL, `collectionRevision` INTEGER NOT NULL, `isNonterminal` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `isStale` INTEGER NOT NULL, `staleTarget` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_index_isNonterminal_collectionRevision` ON `agent_run_index` (`isNonterminal`, `collectionRevision`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_index_runId` ON `agent_run_index` (`runId`)")
    }
}

private const val PROFILE_ID = 1
