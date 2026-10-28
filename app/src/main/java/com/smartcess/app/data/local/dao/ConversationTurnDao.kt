package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.ConversationTurnEntity

@Dao
interface ConversationTurnDao {
    @Upsert suspend fun upsert(turn: ConversationTurnEntity): Long
    @Query("DELETE FROM conversation_turns") suspend fun deleteAll()

    /** Full history for user export (H2: turns were write-only). */
    @Query("SELECT * FROM conversation_turns ORDER BY createdAt ASC")
    suspend fun all(): List<ConversationTurnEntity>
}
