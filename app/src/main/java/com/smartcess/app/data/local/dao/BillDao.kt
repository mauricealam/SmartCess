package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.BillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Upsert suspend fun upsert(bill: BillEntity): Long
    @Update suspend fun update(bill: BillEntity)
    @Query("DELETE FROM bills WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM bills") suspend fun deleteAll()
    @Query("SELECT * FROM bills WHERE id = :id") suspend fun getById(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE archived = 0 ORDER BY dueDate ASC")
    fun observeActive(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE archived = 0 AND dueDate < :today ORDER BY dueDate ASC")
    suspend fun overdue(today: String): List<BillEntity>

    @Query("SELECT * FROM bills WHERE archived = 0 AND dueDate BETWEEN :today AND :upper ORDER BY dueDate ASC")
    suspend fun dueBetween(today: String, upper: String): List<BillEntity>

    @Query("SELECT * FROM bills WHERE provider = :provider AND archived = 0 ORDER BY dueDate DESC LIMIT :limit")
    suspend fun byProvider(provider: String, limit: Int = 5): List<BillEntity>

    @Query("SELECT * FROM bills WHERE provider = :provider AND archived = 0 AND id != :excludeId ORDER BY dueDate DESC LIMIT 1")
    suspend fun previousFor(provider: String, excludeId: Long): BillEntity?

    @Query("UPDATE bills SET archived = 1, updatedAt = :now WHERE id = :id")
    suspend fun archive(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM bills ORDER BY updatedAt DESC") suspend fun allForExport(): List<BillEntity>
}
