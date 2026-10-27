package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.DocumentDao
import com.smartcess.app.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(private val dao: DocumentDao) {

    fun observeAll(): Flow<List<DocumentEntity>> = dao.observeAll()
    suspend fun getById(id: Long): DocumentEntity? = dao.getById(id)
    suspend fun withDeadlines(): List<DocumentEntity> = dao.withDeadlines()

    suspend fun save(document: DocumentEntity): SaveOutcome {
        val titled = document.copy(title = normalizeDocumentTitle(document.title))
        val result = validateDocument(titled.summary, titled.deadline, titled.confidence)
        if (result.hasErrors) return SaveOutcome(null, result)
        return SaveOutcome(dao.upsert(titled), result)
    }

    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
