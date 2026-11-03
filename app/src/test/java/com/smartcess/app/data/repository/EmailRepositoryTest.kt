package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.EmailItemDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The demo inbox is retired: no seeds are ever inserted, and the one-time
 * prune removes only our own seed rows (pair-matched in the DAO query).
 */
class EmailRepositoryTest {

    @Test
    fun pruneDemoSeeds_deletesOnlySeedRows() = runTest {
        val dao = mockk<EmailItemDao>()
        coEvery { dao.deleteDemoSeeds() } returns 3

        EmailRepository(dao).pruneDemoSeeds()

        coVerify(exactly = 1) { dao.deleteDemoSeeds() }
        coVerify(exactly = 0) { dao.upsert(any()) }
        confirmVerified(dao)
    }
}
