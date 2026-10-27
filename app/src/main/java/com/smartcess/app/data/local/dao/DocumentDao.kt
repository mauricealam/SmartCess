package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Upsert suspend fun upsert(document: DocumentEntity): Long
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM documents") suspend fun deleteAll()
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE deadline IS NOT NULL ORDER BY deadline ASC")
    suspend fun withDeadlines(): List<DocumentEntity>

    @Query("SELECT * FROM documents ORDER BY createdAt DESC") suspend fun all(): List<DocumentEntity>
}
