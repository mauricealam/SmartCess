package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.BillDao
import com.smartcess.app.data.local.entity.BillEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Bills with spec validation. Saves never auto-pay; reminders need confirmation upstream. */
@Singleton
class BillRepository @Inject constructor(private val dao: BillDao) {

    fun observeActive(): Flow<List<BillEntity>> = dao.observeActive()
    fun observeArchived(): Flow<List<BillEntity>> = dao.observeArchived()
    suspend fun getById(id: Long): BillEntity? = dao.getById(id)

    suspend fun overdue(): List<BillEntity> = dao.overdue(LocalDate.now().toString())

    suspend fun dueWithinDays(days: Int): List<BillEntity> {
        val today = LocalDate.now()
        return dao.dueBetween(today.toString(), today.plusDays(days.toLong()).toString())
    }

    suspend fun byProvider(provider: String): List<BillEntity> = dao.byProvider(provider)
    suspend fun previousFor(provider: String, excludeId: Long): BillEntity? =
        dao.previousFor(provider, excludeId)

    /** Validates then saves. Returns the row id plus issues; callers must surface issues. */
    suspend fun save(bill: BillEntity): SaveOutcome {
        val result = validateBill(
            provider = bill.provider,
            amount = bill.amount,
            currency = bill.currency,
            dueDate = bill.dueDate,
            requiredAction = bill.requiredAction,
            confidence = bill.confidence,
        )
        if (result.hasErrors) return SaveOutcome(savedId = null, validation = result)
        val id = dao.upsert(bill.copy(updatedAt = System.currentTimeMillis()))
        return SaveOutcome(savedId = id, validation = result)
    }

    /**
     * Offline draft save (spec §16): validation issues are recorded but never
     * block the save. The review screen shows the low-confidence banner and
     * the user corrects fields before the real save.
     */
    suspend fun saveDraft(bill: BillEntity): SaveOutcome {
        val result = validateBill(
            provider = bill.provider,
            amount = bill.amount,
            currency = bill.currency,
            dueDate = bill.dueDate,
            requiredAction = bill.requiredAction,
            confidence = bill.confidence,
        )
        val id = dao.upsert(bill.copy(updatedAt = System.currentTimeMillis()))
        return SaveOutcome(savedId = id, validation = result)
    }

    suspend fun archive(id: Long) = dao.archive(id)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun allForExport(): List<BillEntity> = dao.allForExport()
}

data class SaveOutcome(val savedId: Long?, val validation: ValidationResult)
