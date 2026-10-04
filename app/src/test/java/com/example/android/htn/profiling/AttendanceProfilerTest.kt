package com.example.android.htn.profiling

import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.Attendee
import com.example.android.htn.data.AttendeeRole
import com.example.android.htn.data.CheckIn
import com.example.android.htn.data.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceProfilerTest {

    private val htn2022 = Event(id = 1, name = "HTN 2022", location = "Waterloo", startEpochDay = 100)
    private val htn2023 = Event(id = 2, name = "HTN 2023", location = "Waterloo", startEpochDay = 200)
    private val htn2024 = Event(id = 3, name = "HTN 2024", location = "Waterloo", startEpochDay = 300)
    private val htn2025 = Event(id = 4, name = "HTN 2025", location = "Waterloo", startEpochDay = 400)
    private val htn2026 = Event(id = 5, name = "HTN 2026", location = "Waterloo", startEpochDay = 500)
    private val events = listOf(htn2025, htn2022, htn2026, htn2024, htn2023) // deliberately unsorted

    private fun attendee(id: Long, role: AttendeeRole = AttendeeRole.HACKER) =
        Attendee(id = id, name = "A$id", email = "a$id@example.com", organization = "", role = role, createdAt = 0)

    @Test
    fun noCheckIns_givesEmptyProfile() {
        val profile = AttendanceProfiler.profile(events, emptySet(), todayEpochDay = 1_000)
        assertEquals(0, profile.eventsAttended)
        assertEquals(AttendanceTier.NONE, profile.tier)
        assertNull(profile.firstSeen)
        assertTrue(profile.timeline.isEmpty())
    }

    @Test
    fun eventsBeforeFirstCheckIn_doNotCountAgainstRate() {
        val profile = AttendanceProfiler.profile(events, setOf(3L, 5L), todayEpochDay = 1_000)
        assertEquals(2, profile.eventsAttended)
        assertEquals(3, profile.eligibleEvents) // 2024, 2025, 2026
        assertEquals(2.0 / 3, profile.attendanceRate, 1e-9)
        assertEquals(htn2024, profile.firstSeen)
        assertEquals(htn2026, profile.lastSeen)
    }

    @Test
    fun streaks() {
        // attended 2022, 2023, 2024, missed 2025, attended 2026
        val profile = AttendanceProfiler.profile(events, setOf(1L, 2L, 3L, 5L), todayEpochDay = 1_000)
        assertEquals(3, profile.longestStreak)
        assertEquals(1, profile.currentStreak)
        assertEquals(AttendanceTier.VETERAN, profile.tier)
    }

    @Test
    fun missingMostRecentEvent_breaksCurrentStreak() {
        val profile = AttendanceProfiler.profile(events, setOf(3L, 4L), todayEpochDay = 1_000)
        assertEquals(0, profile.currentStreak)
        assertEquals(2, profile.longestStreak)
    }

    @Test
    fun futureEvents_areIgnoredUntilAttended() {
        val profile = AttendanceProfiler.profile(events, setOf(3L, 4L), todayEpochDay = 450)
        assertEquals(2, profile.eligibleEvents)
        assertEquals(1.0, profile.attendanceRate, 1e-9)
        assertEquals(2, profile.currentStreak)
    }

    @Test
    fun timeline_isMostRecentFirst() {
        val profile = AttendanceProfiler.profile(events, setOf(2L, 4L), todayEpochDay = 1_000)
        assertEquals(listOf(htn2026, htn2025, htn2024, htn2023), profile.timeline.map { it.event })
        assertEquals(listOf(false, true, false, true), profile.timeline.map { it.attended })
    }

    @Test
    fun tierThresholds() {
        assertEquals(AttendanceTier.NONE, AttendanceProfiler.tierFor(0))
        assertEquals(AttendanceTier.FIRST_TIMER, AttendanceProfiler.tierFor(1))
        assertEquals(AttendanceTier.RETURNING, AttendanceProfiler.tierFor(2))
        assertEquals(AttendanceTier.RETURNING, AttendanceProfiler.tierFor(3))
        assertEquals(AttendanceTier.VETERAN, AttendanceProfiler.tierFor(4))
    }

    @Test
    fun eventSummary_splitsFirstTimersFromReturning() {
        val alice = attendee(1)
        val bob = attendee(2, AttendeeRole.MENTOR)
        val carol = attendee(3)
        val checkIns = listOf(
            CheckIn(eventId = 2, attendeeId = 1, checkedInAt = 0), // alice came in 2023
            CheckIn(eventId = 3, attendeeId = 1, checkedInAt = 0),
            CheckIn(eventId = 3, attendeeId = 2, checkedInAt = 0),
            CheckIn(eventId = 4, attendeeId = 3, checkedInAt = 0), // later event doesn't make carol returning
            CheckIn(eventId = 3, attendeeId = 3, checkedInAt = 0),
        )
        val summary = AttendanceProfiler.summarizeEvent(htn2024, events, listOf(alice, bob, carol), checkIns)
        assertEquals(3, summary.checkedIn)
        assertEquals(1, summary.returning)
        assertEquals(2, summary.firstTimers)
        assertEquals(mapOf(AttendeeRole.HACKER to 2, AttendeeRole.MENTOR to 1), summary.byRole)
    }

    @Test
    fun emailValidationAndNormalization() {
        assertTrue(AttendanceRepository.isValidEmail("hacker@uwaterloo.ca"))
        assertTrue(AttendanceRepository.isValidEmail("  first.last+htn@gmail.com "))
        assertFalse(AttendanceRepository.isValidEmail("not-an-email"))
        assertFalse(AttendanceRepository.isValidEmail("a@b"))
        assertEquals("hacker@uwaterloo.ca", AttendanceRepository.normalizeEmail(" Hacker@UWaterloo.ca "))
    }
}
