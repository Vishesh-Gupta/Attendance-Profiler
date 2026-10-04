package com.example.android.htn.data

enum class Role(val label: String) {
    PARTICIPANT("Participant"),
    JUDGE("Judge"),
    VOLUNTEER("Volunteer"),
    ORGANIZER("Organizer");

    /** Volunteers and organizers run check-in, so they can look people up. */
    val canCheckIn: Boolean get() = this == VOLUNTEER || this == ORGANIZER
    /** Only organizers see everyone's profiles, attendance stats and all scores. */
    val canSeeEveryone: Boolean get() = this == ORGANIZER
    val canManageEvents: Boolean get() = this == ORGANIZER
    val canJudge: Boolean get() = this == JUDGE
    /** Judges and organizers see every project submission. */
    val canSeeAllProjects: Boolean get() = this == JUDGE || this == ORGANIZER

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
    /** Inclusive; equal to [startEpochDay] for one-day events. */
    val endEpochDay: Long = startEpochDay,
    val description: String = "",
) {
    fun isOver(todayEpochDay: Long): Boolean = endEpochDay < todayEpochDay
    fun isOn(epochDay: Long): Boolean = epochDay in startEpochDay..endEpochDay
}

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

/** Stored at events/{eventId}/projects/{submittedBy}: one project per person per event. */
data class Project(
    val eventId: String,
    val submittedBy: String,
    val submitterName: String,
    val title: String,
    val description: String,
    val link: String,
    val teamMembers: List<String>,
    val updatedAt: Long,
)

enum class Criterion(val label: String) {
    INNOVATION("Innovation"),
    TECHNICAL("Technical difficulty"),
    DESIGN("Design"),
    IMPACT("Impact"),
}

/** One judge's score for one project. Each criterion is 1–10. */
data class Score(
    val eventId: String,
    val projectId: String,
    val judgeId: String,
    val values: Map<Criterion, Int>,
    val comment: String,
) {
    val total: Int get() = values.values.sum()

    companion object {
        const val MIN = 1
        const val MAX = 10
        val MAX_TOTAL = MAX * Criterion.entries.size
    }
}
