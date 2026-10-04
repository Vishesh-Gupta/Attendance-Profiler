package com.example.android.htn.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An event people can attend, e.g. "Hack the North 2026". [startEpochDay] orders events in time. */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val location: String,
    val startEpochDay: Long,
)

enum class AttendeeRole(val label: String) {
    HACKER("Hacker"),
    MENTOR("Mentor"),
    VOLUNTEER("Volunteer"),
    ORGANIZER("Organizer"),
    SPONSOR("Sponsor"),
}

/** A person, identified across events by their (normalized) email. */
@Entity(tableName = "attendees", indices = [Index(value = ["email"], unique = true)])
data class Attendee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String,
    val organization: String,
    val role: AttendeeRole,
    val createdAt: Long,
)

/** One attendee showing up at one event. An attendee can check in to an event at most once. */
@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Attendee::class,
            parentColumns = ["id"],
            childColumns = ["attendeeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["eventId", "attendeeId"], unique = true), Index("attendeeId")],
)
data class CheckIn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val attendeeId: Long,
    val checkedInAt: Long,
)

data class EventWithCount(
    @Embedded val event: Event,
    val attendeeCount: Int,
)

data class CheckedInAttendee(
    @Embedded val attendee: Attendee,
    val checkedInAt: Long,
)
