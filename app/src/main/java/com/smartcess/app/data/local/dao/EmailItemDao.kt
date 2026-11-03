package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.EmailItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailItemDao {
    @Upsert suspend fun upsert(item: EmailItemEntity): Long
    @Query("DELETE FROM email_items") suspend fun deleteAll()
    @Query("SELECT * FROM email_items WHERE id = :id") suspend fun getById(id: Long): EmailItemEntity?

    @Query("SELECT * FROM email_items ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<EmailItemEntity>>

    @Query("SELECT * FROM email_items WHERE urgency IN ('high','urgent') AND isRead = 0 ORDER BY receivedAt DESC")
    suspend fun unreadUrgent(): List<EmailItemEntity>

    @Query("SELECT COUNT(*) FROM email_items") suspend fun count(): Int

    @Query("UPDATE email_items SET isRead = 1 WHERE id = :id") suspend fun markRead(id: Long)

    /**
     * Retired demo seeds, matched by exact sender + subject pairs (both the
     * original and sample-prefixed bodies). The inbox is read-only, so no
     * user mail can ever match these pairs.
     */
    @Query(
        "DELETE FROM email_items WHERE " +
            "(sender = 'Tenaga Nasional' AND subject = 'Your electricity bill is ready') OR " +
            "(sender = 'City Clinic' AND subject = 'Appointment reminder') OR " +
            "(sender = 'Parcel Express' AND subject = 'Delivery attempted')",
    )
    suspend fun deleteDemoSeeds(): Int

    @Query("SELECT * FROM email_items ORDER BY receivedAt DESC") suspend fun all(): List<EmailItemEntity>
}
