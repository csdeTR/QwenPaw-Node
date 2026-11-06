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
import androidx.room.Transaction
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

    /**
     * 用这一份消息整体替换某个对话的缓存。
     *
     * [saveMessages] 只能按主键覆盖：同一条消息在本地与历史里 id 可能不同，
     * 直接追加会让退出重进后的列表出现重复气泡。
     */
    @Transaction
    suspend fun replaceMessages(chatId: String, messages: List<CachedMessageEntity>) {
        clearMessages(chatId)
        saveMessages(messages)
    }

    @Query("DELETE FROM cached_messages WHERE chatId = :chatId")
    suspend fun clearMessages(chatId: String)
}

@Database(
    entities = [DraftEntity::class, CachedMessageEntity::class],
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
