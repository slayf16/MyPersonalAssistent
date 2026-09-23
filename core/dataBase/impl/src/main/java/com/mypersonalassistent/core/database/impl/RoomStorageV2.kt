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

internal data class ChatSummaryRow(val id: String, val title: String, val updatedAt: Long)

@Database(
    entities = [ChatEntity::class, ProfileEntity::class, TaskMemoryEntity::class, AgentCheckpointEntity::class, AgentRecoveryEntity::class],
    version = 4,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentDao(): AgentDao
}

class RoomChatStorage private constructor(private val database: AppDatabase) : ChatStorage, MemoryStorage, AgentStorage {
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
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

private const val PROFILE_ID = 1
