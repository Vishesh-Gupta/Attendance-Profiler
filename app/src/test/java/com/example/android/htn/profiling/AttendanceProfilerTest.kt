package com.example.android.htn.profiling

import com.example.android.htn.data.CheckIn
import com.example.android.htn.data.CheckInMethod
import com.example.android.htn.data.Event
import com.example.android.htn.data.Registration
import com.example.android.htn.data.RegistrationStatus
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceProfilerTest {

    private val htn2022 = Event(id = "e1", name = "HTN 2022", location = "Waterloo", startEpochDay = 100)
    private val htn2023 = Event(id = "e2", name = "HTN 2023", location = "Waterloo", startEpochDay = 200)
    private val htn2024 = Event(id = "e3", name = "HTN 2024", location = "Waterloo", startEpochDay = 300)
    private val htn2025 = Event(id = "e4", name = "HTN 2025", location = "Waterloo", startEpochDay = 400)
    private val htn2026 = Event(id = "e5", name = "HTN 2026", location = "Waterloo", startEpochDay = 500)
    private val events = listOf(htn2025, htn2022, htn2026, htn2024, htn2023) // deliberately unsorted

    private fun profile(attended: Set<String>, registered: Set<String> = emptySet(), today: Long = 1_000) =
        AttendanceProfiler.profile(events, attended, registered, today)

    private fun user(uid: String, role: Role = Role.PARTICIPANT) = UserProfile(uid, uid, "$uid@x.com", "", role)
    private fun checkIn(eventId: String, uid: String) = CheckIn(eventId, uid, 0, "vol", CheckInMethod.QR)
    private fun approved(eventId: String, uid: String) = Registration(eventId, uid, RegistrationStatus.CONFIRMED)

    @Test
    fun noActivity_givesEmptyProfile() {
        val p = profile(emptySet())
        assertEquals(0, p.eventsAttended)
        assertEquals(AttendanceTier.NONE, p.tier)
        assertNull(p.firstSeen)
        assertTrue(p.timeline.isEmpty())
    }

    @Test
    fun eventsBeforeFirstEngagement_doNotCountAgainstRate() {
        val p = profile(setOf("e3", "e5"))
        assertEquals(2, p.eventsAttended)
        assertEquals(3, p.eligibleEvents) // 2024, 2025, 2026
        assertEquals(2.0 / 3, p.attendanceRate, 1e-9)
        assertEquals(htn2024, p.firstSeen)
        assertEquals(htn2026, p.lastSeen)
    }

    @Test
    fun streaks() {
        // attended 2022, 2023, 2024, missed 2025, attended 2026
        val p = profile(setOf("e1", "e2", "e3", "e5"))
        assertEquals(3, p.longestStreak)
        assertEquals(1, p.currentStreak)
        assertEquals(AttendanceTier.VETERAN, p.tier)
    }

    @Test
    fun missingMostRecentEvent_breaksCurrentStreak() {
        val p = profile(setOf("e3", "e4"))
        assertEquals(0, p.currentStreak)
        assertEquals(2, p.longestStreak)
    }

    @Test
    fun noShows_countRegisteredPastEventsWithoutCheckIn() {
        val p = profile(attended = setOf("e3"), registered = setOf("e2", "e3", "e4"))
        assertEquals(2, p.noShows)
        // First engagement is the 2023 registration, so 2023 through 2026 are eligible.
        assertEquals(4, p.eligibleEvents)
        assertEquals(
            listOf(TimelineStatus.MISSED, TimelineStatus.NO_SHOW, TimelineStatus.ATTENDED, TimelineStatus.NO_SHOW),
            p.timeline.map { it.status },
        )
    }

    @Test
    fun onlyNoShows_stillProducesAProfile() {
        val p = profile(attended = emptySet(), registered = setOf("e4"))
        assertEquals(1, p.noShows)
        assertEquals(0.0, p.attendanceRate, 1e-9)
        assertEquals(AttendanceTier.NONE, p.tier)
        assertNull(p.firstSeen)
    }

    @Test
    fun registrationForTodayOrLater_isNotANoShowYet() {
        val p = profile(attended = setOf("e3"), registered = setOf("e4", "e5"), today = 400)
        assertEquals(0, p.noShows)
        assertEquals(1, p.eligibleEvents)
        assertEquals(1, p.currentStreak)
    }

    @Test
    fun multiDayEventStillRunning_isNotANoShowYet() {
        val weekend = Event(id = "w", name = "HTN weekend", location = "", startEpochDay = 600, endEpochDay = 602)
        val during = AttendanceProfiler.profile(events + weekend, setOf("e5"), setOf("w"), todayEpochDay = 601)
        assertEquals(0, during.noShows)
        val after = AttendanceProfiler.profile(events + weekend, setOf("e5"), setOf("w"), todayEpochDay = 603)
        assertEquals(1, after.noShows)
    }

    @Test
    fun futureEvents_areIgnoredUntilAttended() {
        val p = profile(setOf("e3", "e4"), today = 450)
        assertEquals(2, p.eligibleEvents)
        assertEquals(1.0, p.attendanceRate, 1e-9)
        assertEquals(2, p.currentStreak)
    }

    @Test
    fun timeline_isMostRecentFirst() {
        val p = profile(setOf("e2", "e4"))
        assertEquals(listOf(htn2026, htn2025, htn2024, htn2023), p.timeline.map { it.event })
    }

    @Test
    fun profileByUserId_filtersOtherPeoplesActivity() {
        val p = AttendanceProfiler.profile(
            "ada", events,
            listOf(checkIn("e1", "ada"), checkIn("e2", "bob")),
            listOf(approved("e2", "bob")),
            1_000,
        )
        assertEquals(1, p.eventsAttended)
        assertEquals(0, p.noShows)
    }

    @Test
    fun onlyConfirmedSpotsCanBeNoShows() {
        val registrations = listOf(
            Registration("e2", "ada", RegistrationStatus.APPROVED), // approved but never confirmed
            Registration("e3", "ada", RegistrationStatus.DECLINED),
            Registration("e4", "ada", RegistrationStatus.WAITLISTED),
            Registration("e5", "ada", RegistrationStatus.PENDING),
        )
        val p = AttendanceProfiler.profile("ada", events, emptyList(), registrations, 1_000)
        assertEquals(0, p.noShows)
        assertEquals(AttendanceTier.NONE, p.tier)
        assertEquals(1, AttendanceProfiler.profile("ada", events, emptyList(), listOf(approved("e5", "ada")), 1_000).noShows)
    }

    @Test
    fun tierThresholds() {
        assertEquals(AttendanceTier.NONE, AttendanceProfiler.tierFor(0))
        assertEquals(AttendanceTier.FIRST_TIMER, AttendanceProfiler.tierFor(1))
        assertEquals(AttendanceTier.RETURNING, AttendanceProfiler.tierFor(3))
        assertEquals(AttendanceTier.VETERAN, AttendanceProfiler.tierFor(4))
    }

    @Test
    fun eventSummary() {
        val users = listOf(user("ada"), user("bob", Role.JUDGE), user("cat"), user("dan")).associateBy { it.uid }
        val checkIns = listOf(
            checkIn("e2", "ada"), // ada came in 2023, so she's returning in 2024
            checkIn("e3", "ada"),
            checkIn("e3", "bob"),
            checkIn("e4", "cat"), // a later event doesn't make cat returning
            checkIn("e3", "cat"),
        )
        val registrations = listOf(
            approved("e3", "ada"), approved("e3", "dan"), approved("e4", "bob"),
            Registration("e3", "eve", RegistrationStatus.PENDING), // not accepted: not expected, not counted
        )

        val s = AttendanceProfiler.summarizeEvent(htn2024, events, users, checkIns, registrations)

        assertEquals(3, s.checkedIn)
        assertEquals(2, s.registered)
        assertEquals(1, s.returning)
        assertEquals(2, s.firstTimers)
        assertEquals(2, s.walkIns) // bob and cat didn't register
        assertEquals(1, s.notYetArrived) // dan
        assertEquals(mapOf(Role.PARTICIPANT to 2, Role.JUDGE to 1), s.byRole)
    }
}
