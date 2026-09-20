package com.mypersonalassistent.core.database.impl

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChat
import com.mypersonalassistent.core.database.api.StoredChatSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "chats") data class ChatEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val contextJson: String)
@Dao internal interface ChatDao { @Query("SELECT id, title, updatedAt FROM chats ORDER BY updatedAt DESC, id ASC") fun summaries(): Flow<List<ChatSummaryRow>>; @Query("SELECT * FROM chats WHERE id = :id") suspend fun read(id: String): ChatEntity?; @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(chat: ChatEntity) }
internal data class ChatSummaryRow(val id: String, val title: String, val updatedAt: Long)
@Database(entities = [ChatEntity::class], version = 1, exportSchema = true) internal abstract class AppDatabase : RoomDatabase() { abstract fun chatDao(): ChatDao }
class RoomChatStorage private constructor(private val database: AppDatabase) : ChatStorage {
    override fun observeSummaries(): Flow<List<StoredChatSummary>> = database.chatDao().summaries().map { rows -> rows.map { StoredChatSummary(it.id, it.title, it.updatedAt) } }
    override suspend fun read(id: String): StoredChat? = database.chatDao().read(id)?.let { StoredChat(it.id, it.title, it.createdAt, it.updatedAt, it.contextJson) }
    override suspend fun upsert(chat: StoredChat): StorageResult = runCatching { database.chatDao().upsert(ChatEntity(chat.id, chat.title, chat.createdAt, chat.updatedAt, chat.contextJson)) }.fold({ StorageResult.Success }, { StorageResult.Failure })
    companion object { fun create(context: Context): RoomChatStorage = RoomChatStorage(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "my-personal-assistent.db").build()) }
}
