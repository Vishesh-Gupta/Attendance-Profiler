package com.example.android.htn.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttendanceRepositoryTest {

    private lateinit var database: AttendanceDatabase
    private lateinit var repository: AttendanceRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AttendanceDatabase::class.java).build()
        repository = AttendanceRepository(database) { 42L }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun registeringSameEmailTwice_reusesProfileAndBlocksDuplicateCheckIn() = runTest {
        val htn2025 = repository.createEvent("HTN 2025", "Waterloo", 100)
        val htn2026 = repository.createEvent("HTN 2026", "Waterloo", 200)

        val first = repository.registerAndCheckIn(htn2025, "Ada", "ada@example.com", "UW", AttendeeRole.HACKER)
        val again = repository.registerAndCheckIn(htn2025, "Ada", " ADA@example.com", "UW", AttendeeRole.HACKER)
        val nextYear = repository.registerAndCheckIn(htn2026, "Ada L", "ada@example.com", "", AttendeeRole.MENTOR)

        assertTrue(first is CheckInResult.CheckedIn)
        assertTrue(again is CheckInResult.AlreadyCheckedIn)
        assertTrue(nextYear is CheckInResult.CheckedIn)
        assertEquals(1, repository.attendees.first().size)
        assertEquals(2, repository.checkIns.first().size)
        assertEquals(listOf(1, 1), repository.eventsWithCounts.first().map { it.attendeeCount })
    }

    @Test
    fun deletingEvent_cascadesToCheckIns() = runTest {
        val eventId = repository.createEvent("HTN 2026", "Waterloo", 200)
        repository.registerAndCheckIn(eventId, "Ada", "ada@example.com", "UW", AttendeeRole.HACKER)

        repository.deleteEvent(eventId)

        assertTrue(repository.checkIns.first().isEmpty())
        assertEquals(1, repository.attendees.first().size)
    }
}
