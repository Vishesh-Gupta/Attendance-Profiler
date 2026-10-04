package com.example.android.htn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    @Insert
    suspend fun insertEvent(event: Event): Long

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)

    @Query("SELECT * FROM events WHERE id = :eventId")
    fun observeEvent(eventId: Long): Flow<Event?>

    @Query("SELECT * FROM events ORDER BY startEpochDay, id")
    fun observeEvents(): Flow<List<Event>>

    @Query(
        """
        SELECT e.*, COUNT(c.id) AS attendeeCount
        FROM events e LEFT JOIN check_ins c ON c.eventId = e.id
        GROUP BY e.id
        ORDER BY e.startEpochDay DESC, e.id DESC
        """
    )
    fun observeEventsWithCounts(): Flow<List<EventWithCount>>

    @Insert
    suspend fun insertAttendee(attendee: Attendee): Long

    @Query("SELECT * FROM attendees WHERE email = :email LIMIT 1")
    suspend fun findAttendeeByEmail(email: String): Attendee?

    @Query("SELECT * FROM attendees WHERE id = :attendeeId")
    fun observeAttendee(attendeeId: Long): Flow<Attendee?>

    @Query("SELECT * FROM attendees ORDER BY name COLLATE NOCASE")
    fun observeAttendees(): Flow<List<Attendee>>

    @Query(
        """
        SELECT * FROM attendees
        WHERE name LIKE '%' || :query || '%' OR email LIKE '%' || :query || '%'
            OR organization LIKE '%' || :query || '%'
        ORDER BY name COLLATE NOCASE
        LIMIT 25
        """
    )
    fun searchAttendees(query: String): Flow<List<Attendee>>

    /** Returns -1 when the attendee was already checked in to the event. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckIn(checkIn: CheckIn): Long

    @Query("DELETE FROM check_ins WHERE eventId = :eventId AND attendeeId = :attendeeId")
    suspend fun deleteCheckIn(eventId: Long, attendeeId: Long)

    @Query("SELECT * FROM check_ins")
    fun observeCheckIns(): Flow<List<CheckIn>>

    @Query(
        """
        SELECT a.*, c.checkedInAt FROM attendees a
        JOIN check_ins c ON c.attendeeId = a.id
        WHERE c.eventId = :eventId
        ORDER BY c.checkedInAt DESC
        """
    )
    fun observeCheckedInAttendees(eventId: Long): Flow<List<CheckedInAttendee>>
}
