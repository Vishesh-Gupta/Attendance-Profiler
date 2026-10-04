package com.example.android.htn.profiling

import com.example.android.htn.data.Attendee
import com.example.android.htn.data.AttendeeRole
import com.example.android.htn.data.CheckIn
import com.example.android.htn.data.Event

enum class AttendanceTier(val label: String) {
    NONE("No attendance yet"),
    FIRST_TIMER("First-timer"),
    RETURNING("Returning"),
    VETERAN("Veteran"),
}

/** One row of an attendee's timeline: every event since they first showed up, attended or not. */
data class TimelineEntry(val event: Event, val attended: Boolean)

data class AttendanceProfile(
    val eventsAttended: Int,
    /** Events the attendee could have attended: from their first check-in up to today. */
    val eligibleEvents: Int,
    val attendanceRate: Double,
    val currentStreak: Int,
    val longestStreak: Int,
    val firstSeen: Event?,
    val lastSeen: Event?,
    val tier: AttendanceTier,
    /** Most recent first. */
    val timeline: List<TimelineEntry>,
)

data class EventSummary(
    val checkedIn: Int,
    val firstTimers: Int,
    val returning: Int,
    val byRole: Map<AttendeeRole, Int>,
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
     * Builds an attendee's profile from every known event and the ids of events they checked in to.
     * Events after [todayEpochDay] only count if the attendee already checked in to them.
     */
    fun profile(allEvents: List<Event>, attendedEventIds: Set<Long>, todayEpochDay: Long): AttendanceProfile {
        val sorted = allEvents.sortedWith(chronological)
        val firstIndex = sorted.indexOfFirst { it.id in attendedEventIds }
        if (firstIndex == -1) {
            return AttendanceProfile(0, 0, 0.0, 0, 0, null, null, AttendanceTier.NONE, emptyList())
        }

        val eligible = sorted.drop(firstIndex)
            .filter { it.id in attendedEventIds || it.startEpochDay <= todayEpochDay }
            .map { TimelineEntry(it, attended = it.id in attendedEventIds) }

        var longest = 0
        var run = 0
        for (entry in eligible) {
            run = if (entry.attended) run + 1 else 0
            longest = maxOf(longest, run)
        }
        val current = eligible.asReversed().takeWhile { it.attended }.size
        val attended = eligible.filter { it.attended }

        return AttendanceProfile(
            eventsAttended = attended.size,
            eligibleEvents = eligible.size,
            attendanceRate = attended.size.toDouble() / eligible.size,
            currentStreak = current,
            longestStreak = longest,
            firstSeen = attended.first().event,
            lastSeen = attended.last().event,
            tier = tierFor(attended.size),
            timeline = eligible.asReversed(),
        )
    }

    /** Summarizes who showed up to [event]: first-timers have no check-in at any earlier event. */
    fun summarizeEvent(
        event: Event,
        allEvents: List<Event>,
        checkedIn: List<Attendee>,
        allCheckIns: List<CheckIn>,
    ): EventSummary {
        val earlierEventIds = allEvents.filter { chronological.compare(it, event) < 0 }.map { it.id }.toSet()
        val returningIds = allCheckIns
            .filter { it.eventId in earlierEventIds }
            .map { it.attendeeId }
            .toSet()
        val returning = checkedIn.count { it.id in returningIds }
        return EventSummary(
            checkedIn = checkedIn.size,
            firstTimers = checkedIn.size - returning,
            returning = returning,
            byRole = checkedIn.groupingBy { it.role }.eachCount(),
        )
    }
}
