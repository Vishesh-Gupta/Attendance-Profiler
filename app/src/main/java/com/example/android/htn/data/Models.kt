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
    /** Judges score the projects organizers assign to them. */
    val canJudge: Boolean get() = this == JUDGE

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

    /** Last day teams can be created, joined or left, and approved people can withdraw. */
    val teamDeadlineEpochDay: Long get() = startEpochDay - TEAM_LOCK_DAYS

    /** True until the team deadline has passed. After it, teams are locked and nobody accepted can back out. */
    fun teamsOpen(todayEpochDay: Long): Boolean = todayEpochDay <= teamDeadlineEpochDay

    companion object {
        /** Keep in sync with firebase/firestore.rules (beforeTeamDeadline) and functions/emails.js. */
        const val TEAM_LOCK_DAYS = 14L
    }
}

enum class CheckInMethod { QR, MANUAL }

data class CheckIn(
    val eventId: String,
    val userId: String,
    val checkedInAt: Long,
    val checkedInBy: String,
    val method: CheckInMethod,
)

/**
 * An application to attend an event. Organizers review each one and the applicant is emailed the
 * decision. Approved applicants confirm their spot with the button in that email (CONFIRMED); only
 * confirmed people can form teams or be checked in.
 */
enum class RegistrationStatus(val label: String) {
    PENDING("Pending review"),
    APPROVED("Approved, awaiting confirmation"),
    CONFIRMED("Confirmed"),
    WAITLISTED("Waitlisted"),
    DECLINED("Declined");

    companion object {
        /** Registrations from before applications were reviewed have no status. */
        fun parse(value: String?): RegistrationStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

data class Registration(
    val eventId: String,
    val userId: String,
    val status: RegistrationStatus,
    val registeredAt: Long = 0,
    /** The last decision the applicant was emailed about, set by the onRegistrationDecision function. */
    val notifiedStatus: RegistrationStatus? = null,
    /** "email" when the applicant confirmed with the email button; null if an organizer did it. */
    val confirmedVia: String? = null,
) {
    val confirmed: Boolean get() = status == RegistrationStatus.CONFIRMED
}

sealed interface CheckInResult {
    data class CheckedIn(val user: UserProfile) : CheckInResult
    data class AlreadyCheckedIn(val user: UserProfile) : CheckInResult
    /** Participants must have confirmed their spot; [status] is null if they never applied. */
    data class NotApproved(val user: UserProfile, val status: RegistrationStatus?) : CheckInResult
    data object UnknownUser : CheckInResult
}

data class TeamMember(val uid: String, val name: String)

/**
 * A team's project, stored at events/{eventId}/projects/{id}. The id doubles as the team code that
 * teammates use to join. Each member registered and checked in individually.
 */
data class Project(
    val id: String,
    val eventId: String,
    val title: String,
    val description: String,
    val link: String,
    val members: List<TeamMember>,
    val createdBy: String,
    val assignedJudges: List<String>,
    val updatedAt: Long,
) {
    val memberIds: List<String> get() = members.map { it.uid }
    val teamLabel: String get() = members.joinToString(", ") { it.name }

    companion object {
        /** Keep in sync with firebase/firestore.rules. */
        const val MAX_TEAM_SIZE = 4
    }
}

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

/** One row of a published leaderboard. Unscored projects have no rank or average. */
data class ResultEntry(
    val projectId: String,
    val title: String,
    val members: List<String>,
    val rank: Int?,
    val average: Double?,
    val judgeCount: Int,
)

data class PublishedResults(val entries: List<ResultEntry>, val publishedAt: Long)
