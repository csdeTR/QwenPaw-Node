package io.qwenpaw.node.data.local

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
import kotlinx.coroutines.flow.Flow

/** 本地草稿。每个 (实例, 会话) 一条，用于断网或退出后恢复输入。 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val key: String,
    val instanceId: String,
    val chatId: String,
    val text: String,
    val updatedAt: Long,
)

/** 本地缓存的消息，用于离线查看历史。 */
@Entity(tableName = "cached_messages")
data class CachedMessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val instanceId: String,
    val role: String,
    val kind: String,
    val text: String,
    val payload: String?,
    val createdAt: Long,
    val position: Int,
)

/** 失败待重试的消息，仅在用户手动点击重试时发送。 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instanceId: String,
    val chatId: String,
    val text: String,
    val attachmentUrls: String,
    val createdAt: Long,
    val error: String?,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM drafts WHERE chatId = :chatId AND instanceId = :instanceId LIMIT 1")
    suspend fun draft(instanceId: String, chatId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE instanceId = :instanceId ORDER BY updatedAt DESC")
    fun drafts(instanceId: String): Flow<List<DraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE chatId = :chatId AND instanceId = :instanceId")
    suspend fun deleteDraft(instanceId: String, chatId: String)

    @Query("SELECT * FROM cached_messages WHERE chatId = :chatId ORDER BY position ASC")
    fun messages(chatId: String): Flow<List<CachedMessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE chatId = :chatId ORDER BY position ASC")
    suspend fun messagesOnce(chatId: String): List<CachedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMessages(messages: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE chatId = :chatId")
    suspend fun clearMessages(chatId: String)

    @Insert
    suspend fun enqueue(outbox: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    fun outbox(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE instanceId = :instanceId ORDER BY createdAt ASC")
    fun outbox(instanceId: String): Flow<List<OutboxEntity>>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun removeOutbox(id: Long)

    @Query("UPDATE outbox SET error = :error WHERE id = :id")
    suspend fun markOutboxError(id: Long, error: String?)
}

@Database(
    entities = [DraftEntity::class, CachedMessageEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "qwenpaw-node.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
