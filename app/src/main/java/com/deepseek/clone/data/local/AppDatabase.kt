package com.deepseek.clone.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val modelType: String = "default",
    val source: String = "local"          // local / imported
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(
    entity = SessionEntity::class,
    parentColumns = ["id"],
    childColumns = ["sessionId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("sessionId"), Index("messageId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val sessionId: String,
    val messageId: Int = 0,               // 服务端会话内 ID；本地导入从 1 递增
    val parentId: Int? = null,
    val role: String,                     // user / assistant
    val content: String,
    val thinking: String? = null,
    val thinkingElapsed: Double? = null,
    val searchResultsJson: String? = null,
    val modelType: String? = null,
    val thinkingEnabled: Boolean = false,
    val searchEnabled: Boolean = false,
    val status: String = "FINISHED",
    val insertedAt: Double = 0.0,
    val fileRefsJson: String? = null,
    val isError: Boolean = false
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun getSessions(limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY messageId ASC, uid ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY messageId ASC, uid ASC")
    suspend fun getMessages(sessionId: String): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("SELECT MAX(messageId) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxMessageId(sessionId: String): Int?

    @Transaction
    suspend fun replaceMessages(sessionId: String, messages: List<MessageEntity>) {
        deleteMessages(sessionId)
        for (m in messages) insertMessage(m)
    }
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
