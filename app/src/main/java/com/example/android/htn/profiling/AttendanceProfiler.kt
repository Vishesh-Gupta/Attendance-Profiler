package com.example.android.htn.profiling

import com.example.android.htn.data.CheckIn
import com.example.android.htn.data.Event
import com.example.android.htn.data.Registration
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile

enum class AttendanceTier(val label: String) {
    NONE("No attendance yet"),
    FIRST_TIMER("First-timer"),
    RETURNING("Returning"),
    VETERAN("Veteran"),
}

enum class TimelineStatus { ATTENDED, NO_SHOW, MISSED }

/** One row of a person's timeline: every event since they first engaged, attended or not. */
data class TimelineEntry(val event: Event, val status: TimelineStatus)

data class AttendanceProfile(
    val eventsAttended: Int,
    /** Events the person could have attended: from their first registration or check-in up to today. */
    val eligibleEvents: Int,
    val attendanceRate: Double,
    /** Past events they registered for but didn't check in to. */
    val noShows: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val firstSeen: Event?,
    val lastSeen: Event?,
    val tier: AttendanceTier,
    /** Most recent first. */
    val timeline: List<TimelineEntry>,
)

data class EventSummary(
    val registered: Int,
    val checkedIn: Int,
    val firstTimers: Int,
    val returning: Int,
    /** Checked in without registering. */
    val walkIns: Int,
    /** Registered people who haven't checked in. */
    val notYetArrived: Int,
    val byRole: Map<Role, Int>,
)

object AttendanceProfiler {

    private val chronological = compareBy<Event>({ it.startEpochDay }, { it.id })

    fun tierFor(eventsAttended: Int): AttendanceTier = when {
        eventsAttended <= 0 -> AttendanceTier.NONE
        eventsAttended == 1 -> AttendanceTier.FIRST_TIMER
        eventsAttended <= 3 -> AttendanceTier.RETURNING
        else -> AttendanceTier.VETERAN
    }

    /**
     * Builds a person's profile. Events before their first engagement don't count against them, and
     * events that haven't ended by [todayEpochDay] only count once they've checked in. An event is a no-show once
     * it has ended without a check-in from someone who registered.
     */
    fun profile(
        allEvents: List<Event>,
        attendedEventIds: Set<String>,
        registeredEventIds: Set<String>,
        todayEpochDay: Long,
    ): AttendanceProfile {
        fun statusOf(event: Event): TimelineStatus? = when {
            event.id in attendedEventIds -> TimelineStatus.ATTENDED
            !event.isOver(todayEpochDay) -> null // ongoing or upcoming: no verdict yet
            event.id in registeredEventIds -> TimelineStatus.NO_SHOW
            else -> TimelineStatus.MISSED
        }

        val sorted = allEvents.sortedWith(chronological)
        val firstIndex = sorted.indexOfFirst {
            val status = statusOf(it)
            status == TimelineStatus.ATTENDED || status == TimelineStatus.NO_SHOW
        }
        if (firstIndex == -1) {
            return AttendanceProfile(0, 0, 0.0, 0, 0, 0, null, null, AttendanceTier.NONE, emptyList())
        }

        val eligible = sorted.drop(firstIndex).mapNotNull { event -> statusOf(event)?.let { TimelineEntry(event, it) } }

        var longest = 0
        var run = 0
        for (entry in eligible) {
            run = if (entry.status == TimelineStatus.ATTENDED) run + 1 else 0
            longest = maxOf(longest, run)
        }
        val current = eligible.asReversed().takeWhile { it.status == TimelineStatus.ATTENDED }.size
        val attended = eligible.filter { it.status == TimelineStatus.ATTENDED }

        return AttendanceProfile(
            eventsAttended = attended.size,
            eligibleEvents = eligible.size,
            attendanceRate = attended.size.toDouble() / eligible.size,
            noShows = eligible.count { it.status == TimelineStatus.NO_SHOW },
            currentStreak = current,
            longestStreak = longest,
            firstSeen = attended.firstOrNull()?.event,
            lastSeen = attended.lastOrNull()?.event,
            tier = tierFor(attended.size),
            timeline = eligible.asReversed(),
        )
    }

    fun profile(
        userId: String,
        allEvents: List<Event>,
        checkIns: List<CheckIn>,
        registrations: List<Registration>,
        todayEpochDay: Long,
    ): AttendanceProfile = profile(
        allEvents,
        checkIns.filter { it.userId == userId }.map { it.eventId }.toSet(),
        registrations.filter { it.userId == userId }.map { it.eventId }.toSet(),
        todayEpochDay,
    )

    /** Summarizes who showed up to [event]: first-timers have no check-in at any earlier event. */
    fun summarizeEvent(
        event: Event,
        allEvents: List<Event>,
        users: Map<String, UserProfile>,
        allCheckIns: List<CheckIn>,
        allRegistrations: List<Registration>,
    ): EventSummary {
        val earlierEventIds = allEvents.filter { chronological.compare(it, event) < 0 }.map { it.id }.toSet()
        val returningIds = allCheckIns.filter { it.eventId in earlierEventIds }.map { it.userId }.toSet()
        val checkedInIds = allCheckIns.filter { it.eventId == event.id }.map { it.userId }.toSet()
        val registeredIds = allRegistrations.filter { it.eventId == event.id }.map { it.userId }.toSet()
        val returning = checkedInIds.count { it in returningIds }
        return EventSummary(
            registered = registeredIds.size,
            checkedIn = checkedInIds.size,
            firstTimers = checkedInIds.size - returning,
            returning = returning,
            walkIns = (checkedInIds - registeredIds).size,
            notYetArrived = (registeredIds - checkedInIds).size,
            byRole = checkedInIds.mapNotNull { users[it]?.role }.groupingBy { it }.eachCount(),
        )
    }
}
