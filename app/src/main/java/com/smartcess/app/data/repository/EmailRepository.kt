package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.EmailItemDao
import com.smartcess.app.data.local.entity.EmailItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local email store. MVP seeds a demo inbox; Gmail/Outlook arrive later.
 * Read-only surface: never send, reply, delete, or forward.
 */
@Singleton
class EmailRepository @Inject constructor(private val dao: EmailItemDao) {

    fun observeAll(): Flow<List<EmailItemEntity>> = dao.observeAll()
    suspend fun getById(id: Long): EmailItemEntity? = dao.getById(id)
    suspend fun unreadUrgent(): List<EmailItemEntity> = dao.unreadUrgent()
    suspend fun markRead(id: Long) = dao.markRead(id)
    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Removes the retired demo seeds (matched by sender + subject, so both
     * original and sample-prefixed bodies are caught). The inbox is
     * read-only and nothing else can insert mail, so this only ever removes
     * our own seeds. Safe to call on every launch; matches zero rows after.
     */
    suspend fun pruneDemoSeeds() {
        dao.deleteDemoSeeds()
    }
}
