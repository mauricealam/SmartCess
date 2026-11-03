package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.EmailItemDao
import com.smartcess.app.data.local.entity.EmailItemEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo seeds must self-identify as samples: the Tenaga seed talks about an
 * electricity bill, and users otherwise read it as a bill they never added.
 */
class EmailRepositoryTest {

    @Test
    fun emptyInbox_seedsThreeSampleEmails() = runTest {
        val dao = mockk<EmailItemDao>()
        coEvery { dao.count() } returns 0
        coEvery { dao.upsert(any()) } returns 1L
        coEvery { dao.refreshSeedBody(any(), any(), any(), any()) } returns 0

        EmailRepository(dao).seedDemoInboxIfEmpty()

        val items = mutableListOf<EmailItemEntity>()
        coVerify(exactly = 3) { dao.upsert(capture(items)) }
        assertEquals(3, items.size)
        assertTrue(items.all { it.body.startsWith(EmailRepository.SAMPLE_PREFIX) })
        coVerify(exactly = 3) { dao.refreshSeedBody(any(), any(), any(), any()) }
    }

    @Test
    fun nonEmptyInbox_skipsSeedButRefreshesLegacyBodies() = runTest {
        val dao = mockk<EmailItemDao>()
        coEvery { dao.count() } returns 3
        coEvery { dao.refreshSeedBody(any(), any(), any(), any()) } returns 1

        EmailRepository(dao).seedDemoInboxIfEmpty()

        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 3) { dao.refreshSeedBody(any(), any(), any(), any()) }
    }

    @Test
    fun seedBodies_coverAllDemoSenders() = runTest {
        val dao = mockk<EmailItemDao>()
        coEvery { dao.count() } returns 0
        coEvery { dao.upsert(any()) } returns 1L
        coEvery { dao.refreshSeedBody(any(), any(), any(), any()) } returns 0

        EmailRepository(dao).seedDemoInboxIfEmpty()

        val senders = mutableListOf<String>()
        coVerify(exactly = 3) { dao.refreshSeedBody(capture(senders), any(), any(), any()) }
        assertEquals(
            listOf("City Clinic", "Parcel Express", "Tenaga Nasional"),
            senders.sorted(),
        )
        assertTrue(senders.size == senders.toSet().size)
    }
}
