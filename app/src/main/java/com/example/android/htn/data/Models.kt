package com.example.android.htn.data

enum class Role(val label: String) {
    PARTICIPANT("Participant"),
    JUDGE("Judge"),
    VOLUNTEER("Volunteer"),
    ORGANIZER("Organizer");

    /** Judges, volunteers and organizers can see people and attendance. */
    val isStaff: Boolean get() = this != PARTICIPANT
    val canCheckIn: Boolean get() = this == VOLUNTEER || this == ORGANIZER
    val canManageEvents: Boolean get() = this == ORGANIZER

    companion object {
        fun parse(value: String?): Role = entries.firstOrNull { it.name == value } ?: PARTICIPANT
    }
}

data class UserProfile(
    val uid: String,
    val name: String,
    val email: String,
    val organization: String,
    val role: Role,
)

data class Event(
    val id: String,
    val name: String,
    val location: String,
    val startEpochDay: Long,
)

enum class CheckInMethod { QR, MANUAL }

data class CheckIn(
    val eventId: String,
    val userId: String,
    val checkedInAt: Long,
    val checkedInBy: String,
    val method: CheckInMethod,
)

data class Registration(
    val eventId: String,
    val userId: String,
)

sealed interface CheckInResult {
    data class CheckedIn(val user: UserProfile) : CheckInResult
    data class AlreadyCheckedIn(val user: UserProfile) : CheckInResult
    data object UnknownUser : CheckInResult
}
