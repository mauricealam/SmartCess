package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationTurnDao {
    @Upsert suspend fun upsert(turn: ConversationTurnEntity): Long
    @Query("DELETE FROM conversation_turns") suspend fun deleteAll()

    @Query("SELECT * FROM conversation_turns ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ConversationTurnEntity>>
}
