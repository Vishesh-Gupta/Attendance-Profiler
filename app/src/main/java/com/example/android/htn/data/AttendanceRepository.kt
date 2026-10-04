package com.example.android.htn.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Cloud Firestore layout (see firebase/firestore.rules for who can do what):
 *
 *   users/{uid}                                  name, email, organization, role, createdAt
 *   events/{eventId}                             name, location, description, startEpochDay, endEpochDay,
 *                                                createdBy, createdAt
 *   events/{eventId}/checkIns/{uid}              userId, checkedInAt, checkedInBy, method
 *   events/{eventId}/registrations/{uid}         userId, registeredAt, status, reviewedBy, reviewedAt,
 *                                                notifiedStatus, notifiedAt (set by Cloud Functions)
 *   events/{eventId}/projects/{teamCode}         title, description, link, memberIds, members {uid: name},
 *                                                createdBy, assignedJudges, createdAt, updatedAt
 *   events/{eventId}/projects/{teamCode}/scores/{judgeUid}
 *                                                judgeId, innovation, technical, design, impact, comment,
 *                                                updatedAt
 *   events/{eventId}/teamMembers/{uid}           projectId (at most one team per person per event)
 *   events/{eventId}/results/leaderboard         entries, publishedAt, publishedBy
 */
class AttendanceRepository(private val db: FirebaseFirestore) {

    private val users = db.collection("users")
    private val events = db.collection("events")

    fun user(uid: String): Flow<UserProfile?> =
        users.document(uid).snapshots().map { it.toUser() }.logErrors(null)

    /** Volunteers and organizers only. */
    fun users(): Flow<List<UserProfile>> =
        users.orderBy("name").snapshots().map { s -> s.documents.mapNotNull { it.toUser() } }.logErrors(emptyList())

    suspend fun updateProfile(uid: String, name: String, organization: String) {
        users.document(uid).update(mapOf("name" to name.trim(), "organization" to organization.trim())).await()
    }

    suspend fun setRole(uid: String, role: Role) {
        users.document(uid).update("role", role.name).await()
    }

    fun events(): Flow<List<Event>> =
        events.orderBy("startEpochDay", Query.Direction.DESCENDING).snapshots()
            .map { s -> s.documents.mapNotNull { it.toEvent() } }.logErrors(emptyList())

    fun event(eventId: String): Flow<Event?> =
        events.document(eventId).snapshots().map { it.toEvent() }.logErrors(null)

    suspend fun createEvent(
        name: String,
        location: String,
        description: String,
        startEpochDay: Long,
        endEpochDay: Long,
        createdBy: String,
    ): String {
        val ref = events.document()
        ref.set(
            mapOf(
                "name" to name.trim(),
                "location" to location.trim(),
                "description" to description.trim(),
                "startEpochDay" to startEpochDay,
                "endEpochDay" to endEpochDay,
                "createdBy" to createdBy,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        return ref.id
    }

    /** Organizers only. Firestore doesn't delete subcollections with their parent, so remove them first. */
    suspend fun deleteEvent(eventId: String) {
        val ref = events.document(eventId)
        val projects = ref.collection("projects").get().await().documents
        val scores = projects.flatMap { it.reference.collection("scores").get().await().documents }
        val children = scores + projects +
            listOf("teamMembers", "results", "checkIns", "registrations").flatMap {
                ref.collection(it).get().await().documents
            }
        children.chunked(400).forEach { chunk ->
            db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await()
        }
        ref.delete().await()
    }

    /** Volunteers and organizers only. */
    fun allCheckIns(): Flow<List<CheckIn>> =
        db.collectionGroup("checkIns").snapshots()
            .map { s -> s.documents.mapNotNull { it.toCheckIn() } }.logErrors(emptyList())

    /** Volunteers and organizers only. */
    fun allRegistrations(): Flow<List<Registration>> =
        db.collectionGroup("registrations").snapshots()
            .map { s -> s.documents.mapNotNull { it.toRegistration() } }.logErrors(emptyList())

    fun checkInsOf(uid: String): Flow<List<CheckIn>> =
        db.collectionGroup("checkIns").whereEqualTo("userId", uid).snapshots()
            .map { s -> s.documents.mapNotNull { it.toCheckIn() } }.logErrors(emptyList())

    fun registrationsOf(uid: String): Flow<List<Registration>> =
        db.collectionGroup("registrations").whereEqualTo("userId", uid).snapshots()
            .map { s -> s.documents.mapNotNull { it.toRegistration() } }.logErrors(emptyList())

    /** Applies to attend. Organizers then approve, waitlist or decline. */
    suspend fun register(eventId: String, uid: String) {
        events.document(eventId).collection("registrations").document(uid).set(
            mapOf(
                "userId" to uid,
                "registeredAt" to FieldValue.serverTimestamp(),
                "status" to RegistrationStatus.PENDING.name,
            )
        ).await()
    }

    /**
     * Organizers only: records a decision for someone who never applied, such as a walk-in. Like
     * [setRegistrationStatus], this emails them.
     */
    suspend fun registerOnBehalf(eventId: String, uid: String, status: RegistrationStatus, reviewer: String) {
        events.document(eventId).collection("registrations").document(uid).set(
            mapOf(
                "userId" to uid,
                "registeredAt" to FieldValue.serverTimestamp(),
                "status" to status.name,
                "reviewedBy" to reviewer,
                "reviewedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /**
     * Organizers only. Changing the status to approved, waitlisted or declined emails the applicant
     * (see firebase/functions).
     */
    suspend fun setRegistrationStatus(eventId: String, uid: String, status: RegistrationStatus, reviewer: String) {
        events.document(eventId).collection("registrations").document(uid).update(
            mapOf(
                "status" to status.name,
                "reviewedBy" to reviewer,
                "reviewedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    suspend fun unregister(eventId: String, uid: String) {
        events.document(eventId).collection("registrations").document(uid).delete().await()
    }

    /**
     * Volunteers and organizers only. Participants need an approved application. Needs a connection,
     * since it checks for an existing check-in.
     */
    suspend fun checkIn(eventId: String, userId: String, checkedInBy: String, method: CheckInMethod): CheckInResult {
        val userRef = users.document(userId)
        val checkInRef = events.document(eventId).collection("checkIns").document(userId)
        val registrationRef = events.document(eventId).collection("registrations").document(userId)
        return db.runTransaction { tx ->
            val user = tx.get(userRef).toUser() ?: return@runTransaction CheckInResult.UnknownUser
            if (tx.get(checkInRef).exists()) return@runTransaction CheckInResult.AlreadyCheckedIn(user)
            if (user.role == Role.PARTICIPANT) {
                val registration = tx.get(registrationRef).toRegistration()
                if (registration?.approved != true) {
                    return@runTransaction CheckInResult.NotApproved(user, registration?.status)
                }
            }
            tx.set(
                checkInRef,
                mapOf(
                    "userId" to userId,
                    "checkedInAt" to FieldValue.serverTimestamp(),
                    "checkedInBy" to checkedInBy,
                    "method" to method.name,
                ),
            )
            CheckInResult.CheckedIn(user)
        }.await()
    }

    suspend fun undoCheckIn(eventId: String, userId: String) {
        events.document(eventId).collection("checkIns").document(userId).delete().await()
    }

    private fun projectRef(eventId: String, projectId: String) =
        events.document(eventId).collection("projects").document(projectId)

    private fun membershipRef(eventId: String, uid: String) =
        events.document(eventId).collection("teamMembers").document(uid)

    private fun Query.projects(): Flow<List<Project>> =
        snapshots().map { s -> s.documents.mapNotNull { it.toProject() } }.logErrors(emptyList())

    /** Organizers only. */
    fun projectsForEvent(eventId: String): Flow<List<Project>> = events.document(eventId).collection("projects").projects()

    /** Organizers only. */
    fun allProjects(): Flow<List<Project>> = db.collectionGroup("projects").projects()

    /** Projects whose team includes [uid], across all events. */
    fun projectsOf(uid: String): Flow<List<Project>> =
        db.collectionGroup("projects").whereArrayContains("memberIds", uid).projects()

    /** Projects assigned to judge [judgeId], across all events. */
    fun assignedProjects(judgeId: String): Flow<List<Project>> =
        db.collectionGroup("projects").whereArrayContains("assignedJudges", judgeId).projects()

    fun assignedProjects(eventId: String, judgeId: String): Flow<List<Project>> =
        events.document(eventId).collection("projects").whereArrayContains("assignedJudges", judgeId).projects()

    fun project(eventId: String, projectId: String): Flow<Project?> =
        projectRef(eventId, projectId).snapshots().map { it.toProject() }.logErrors(null)

    /** The team [uid] is on at [eventId], if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun teamOf(eventId: String, uid: String): Flow<Project?> =
        membershipRef(eventId, uid).snapshots()
            .map { it.getString("projectId") }
            .distinctUntilChanged()
            .flatMapLatest { projectId -> if (projectId == null) flowOf(null) else project(eventId, projectId) }
            .logErrors(null)

    /**
     * Starts a team project with [me] as its first member. Returns the team code. [me] must be
     * approved for the event and not on another team.
     */
    suspend fun createProject(eventId: String, me: UserProfile, title: String, description: String, link: String): String {
        val code = TeamCode.generate()
        db.batch().apply {
            set(
                projectRef(eventId, code),
                mapOf(
                    "title" to title.trim(),
                    "description" to description.trim(),
                    "link" to link.trim(),
                    "memberIds" to listOf(me.uid),
                    "members" to mapOf(me.uid to me.name),
                    "createdBy" to me.uid,
                    "assignedJudges" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            set(membershipRef(eventId, me.uid), mapOf("projectId" to code))
        }.commit().await()
        return code
    }

    suspend fun updateProject(eventId: String, projectId: String, title: String, description: String, link: String) {
        projectRef(eventId, projectId).update(
            mapOf(
                "title" to title.trim(),
                "description" to description.trim(),
                "link" to link.trim(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /**
     * Adds [me] to the team with [code]. [me] must be approved for the event, and must have left any
     * other team for this event first. People can't read a team before joining it, so the reasons a
     * join is refused are reported together.
     */
    suspend fun joinTeam(eventId: String, code: String, me: UserProfile) {
        try {
            db.batch().apply {
                update(
                    projectRef(eventId, code),
                    FieldPath.of("memberIds"), FieldValue.arrayUnion(me.uid),
                    FieldPath.of("members", me.uid), me.name,
                )
                set(membershipRef(eventId, me.uid), mapOf("projectId" to code))
            }.commit().await()
        } catch (e: FirebaseFirestoreException) {
            throw IllegalStateException(
                when (e.code) {
                    FirebaseFirestoreException.Code.NOT_FOUND -> "No team has that code for this event."
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Couldn't join. The team may be full (max ${Project.MAX_TEAM_SIZE}), you may still be on " +
                            "another team (leave it first), or your application hasn't been approved yet."
                    else -> e.localizedMessage ?: "Couldn't join the team."
                },
                e,
            )
        }
    }

    /** Removes [me] from [project]; the last member leaving deletes the project. */
    suspend fun leaveTeam(project: Project, me: UserProfile) {
        val ref = projectRef(project.eventId, project.id)
        db.batch().apply {
            if (project.memberIds == listOf(me.uid)) {
                delete(ref)
            } else {
                update(
                    ref,
                    FieldPath.of("memberIds"), FieldValue.arrayRemove(me.uid),
                    FieldPath.of("members", me.uid), FieldValue.delete(),
                )
            }
            delete(membershipRef(project.eventId, me.uid))
        }.commit().await()
    }

    /** Organizers only: removes the project, its scores and its members' team records. */
    suspend fun deleteProject(project: Project) {
        val ref = projectRef(project.eventId, project.id)
        val scores = ref.collection("scores").get().await().documents.map { it.reference }
        db.batch().apply {
            scores.forEach { delete(it) }
            project.memberIds.forEach { delete(membershipRef(project.eventId, it)) }
            delete(ref)
        }.commit().await()
    }

    /** Organizers only. */
    suspend fun setAssignedJudges(eventId: String, assignments: Map<String, List<String>>) {
        assignments.entries.chunked(400).forEach { chunk ->
            db.batch().apply {
                chunk.forEach { (projectId, judges) -> update(projectRef(eventId, projectId), "assignedJudges", judges) }
            }.commit().await()
        }
    }

    /** Organizers only. */
    fun allScores(): Flow<List<Score>> =
        db.collectionGroup("scores").snapshots()
            .map { s -> s.documents.mapNotNull { it.toScore() } }.logErrors(emptyList())

    fun scoresBy(judgeId: String): Flow<List<Score>> =
        db.collectionGroup("scores").whereEqualTo("judgeId", judgeId).snapshots()
            .map { s -> s.documents.mapNotNull { it.toScore() } }.logErrors(emptyList())

    /** Team members, once results are published. */
    fun scoresForProject(eventId: String, projectId: String): Flow<List<Score>> =
        projectRef(eventId, projectId).collection("scores").snapshots()
            .map { s -> s.documents.mapNotNull { it.toScore() } }.logErrors(emptyList())

    /** Judges only, for projects assigned to them; replaces the judge's previous score. */
    suspend fun saveScore(eventId: String, projectId: String, judgeId: String, values: Map<Criterion, Int>, comment: String) {
        projectRef(eventId, projectId).collection("scores").document(judgeId).set(
            mapOf(
                "judgeId" to judgeId,
                "innovation" to values.getValue(Criterion.INNOVATION),
                "technical" to values.getValue(Criterion.TECHNICAL),
                "design" to values.getValue(Criterion.DESIGN),
                "impact" to values.getValue(Criterion.IMPACT),
                "comment" to comment.trim(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    private fun resultsRef(eventId: String) = events.document(eventId).collection("results").document("leaderboard")

    fun results(eventId: String): Flow<PublishedResults?> =
        resultsRef(eventId).snapshots().map { it.toResults() }.logErrors(null)

    /** Organizers only. Publishing again replaces the previous snapshot. */
    suspend fun publishResults(eventId: String, entries: List<ResultEntry>, publishedBy: String) {
        resultsRef(eventId).set(
            mapOf(
                "entries" to entries.map {
                    mapOf(
                        "projectId" to it.projectId,
                        "title" to it.title,
                        "members" to it.members,
                        "rank" to it.rank,
                        "average" to it.average,
                        "judgeCount" to it.judgeCount,
                    )
                },
                "publishedAt" to FieldValue.serverTimestamp(),
                "publishedBy" to publishedBy,
            )
        ).await()
    }

    suspend fun unpublishResults(eventId: String) {
        resultsRef(eventId).delete().await()
    }

    private fun <T> Flow<T>.logErrors(fallback: T): Flow<T> = catch { e ->
        // Typically PERMISSION_DENIED right after sign-out or a role change.
        Log.w("AttendanceRepository", "Firestore listener failed", e)
        emit(fallback)
    }
}

private fun DocumentSnapshot.toUser(): UserProfile? {
    if (!exists()) return null
    return UserProfile(
        uid = id,
        name = getString("name").orEmpty(),
        email = getString("email").orEmpty(),
        organization = getString("organization").orEmpty(),
        role = Role.parse(getString("role")),
    )
}

private fun DocumentSnapshot.toEvent(): Event? {
    if (!exists()) return null
    val start = getLong("startEpochDay") ?: return null
    return Event(
        id = id,
        name = getString("name").orEmpty(),
        location = getString("location").orEmpty(),
        startEpochDay = start,
        // Events created before multi-day support have no end date.
        endEpochDay = getLong("endEpochDay") ?: start,
        description = getString("description").orEmpty(),
    )
}

private fun DocumentSnapshot.toCheckIn(): CheckIn? {
    val eventId = reference.parent.parent?.id ?: return null
    return CheckIn(
        eventId = eventId,
        userId = getString("userId") ?: return null,
        checkedInAt = getTimestamp("checkedInAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?.toDate()?.time ?: 0L,
        checkedInBy = getString("checkedInBy").orEmpty(),
        method = if (getString("method") == CheckInMethod.QR.name) CheckInMethod.QR else CheckInMethod.MANUAL,
    )
}

private fun DocumentSnapshot.toRegistration(): Registration? {
    if (!exists()) return null
    val eventId = reference.parent.parent?.id ?: return null
    return Registration(
        eventId = eventId,
        userId = getString("userId") ?: return null,
        status = RegistrationStatus.parse(getString("status")),
        registeredAt = getTimestamp("registeredAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
        notifiedStatus = getString("notifiedStatus")?.let { RegistrationStatus.parse(it) },
    )
}

private fun DocumentSnapshot.toProject(): Project? {
    if (!exists()) return null
    val memberIds = (get("memberIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
    val names = (get("members") as? Map<*, *>).orEmpty()
    return Project(
        id = id,
        eventId = reference.parent.parent?.id ?: return null,
        title = getString("title").orEmpty(),
        description = getString("description").orEmpty(),
        link = getString("link").orEmpty(),
        members = memberIds.map { TeamMember(it, names[it] as? String ?: "Teammate") },
        createdBy = getString("createdBy").orEmpty(),
        assignedJudges = (get("assignedJudges") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        updatedAt = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
    )
}

private fun DocumentSnapshot.toResults(): PublishedResults? {
    if (!exists()) return null
    val entries = (get("entries") as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        ResultEntry(
            projectId = m["projectId"] as? String ?: return@mapNotNull null,
            title = m["title"] as? String ?: "",
            members = (m["members"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            rank = (m["rank"] as? Number)?.toInt(),
            average = (m["average"] as? Number)?.toDouble(),
            judgeCount = (m["judgeCount"] as? Number)?.toInt() ?: 0,
        )
    }
    val publishedAt = getTimestamp("publishedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L
    return PublishedResults(entries, publishedAt)
}

private fun DocumentSnapshot.toScore(): Score? {
    val projectRef = reference.parent.parent ?: return null
    val eventId = projectRef.parent.parent?.id ?: return null
    val fields = mapOf(
        Criterion.INNOVATION to "innovation",
        Criterion.TECHNICAL to "technical",
        Criterion.DESIGN to "design",
        Criterion.IMPACT to "impact",
    )
    return Score(
        eventId = eventId,
        projectId = projectRef.id,
        judgeId = getString("judgeId") ?: return null,
        values = fields.mapValues { (_, field) -> getLong(field)?.toInt() ?: return null },
        comment = getString("comment").orEmpty(),
    )
}
