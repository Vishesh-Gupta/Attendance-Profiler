package com.example.android.htn.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

sealed interface CheckInResult {
    data class CheckedIn(val attendee: Attendee) : CheckInResult
    data class AlreadyCheckedIn(val attendee: Attendee) : CheckInResult
}

class AttendanceRepository(
    private val database: AttendanceDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.attendanceDao()

    val events: Flow<List<Event>> = dao.observeEvents()
    val eventsWithCounts: Flow<List<EventWithCount>> = dao.observeEventsWithCounts()
    val attendees: Flow<List<Attendee>> = dao.observeAttendees()
    val checkIns: Flow<List<CheckIn>> = dao.observeCheckIns()

    fun event(eventId: Long): Flow<Event?> = dao.observeEvent(eventId)
    fun attendee(attendeeId: Long): Flow<Attendee?> = dao.observeAttendee(attendeeId)
    fun checkedInAttendees(eventId: Long): Flow<List<CheckedInAttendee>> =
        dao.observeCheckedInAttendees(eventId)
    fun searchAttendees(query: String): Flow<List<Attendee>> = dao.searchAttendees(query.trim())

    suspend fun createEvent(name: String, location: String, startEpochDay: Long): Long =
        dao.insertEvent(Event(name = name.trim(), location = location.trim(), startEpochDay = startEpochDay))

    suspend fun deleteEvent(eventId: Long) = dao.deleteEvent(eventId)

    suspend fun checkIn(eventId: Long, attendee: Attendee): CheckInResult {
        val rowId = dao.insertCheckIn(CheckIn(eventId = eventId, attendeeId = attendee.id, checkedInAt = clock()))
        return if (rowId == -1L) CheckInResult.AlreadyCheckedIn(attendee) else CheckInResult.CheckedIn(attendee)
    }

    /**
     * Checks a walk-up attendee in. If someone with the same email already has a profile,
     * that profile is reused so their attendance history stays in one place.
     */
    suspend fun registerAndCheckIn(
        eventId: Long,
        name: String,
        email: String,
        organization: String,
        role: AttendeeRole,
    ): CheckInResult = database.withTransaction {
        val normalizedEmail = normalizeEmail(email)
        val attendee = dao.findAttendeeByEmail(normalizedEmail) ?: Attendee(
            name = name.trim(),
            email = normalizedEmail,
            organization = organization.trim(),
            role = role,
            createdAt = clock(),
        ).let { it.copy(id = dao.insertAttendee(it)) }
        checkIn(eventId, attendee)
    }

    suspend fun undoCheckIn(eventId: Long, attendeeId: Long) = dao.deleteCheckIn(eventId, attendeeId)

    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun normalizeEmail(email: String): String = email.trim().lowercase()
        fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())
    }
}
