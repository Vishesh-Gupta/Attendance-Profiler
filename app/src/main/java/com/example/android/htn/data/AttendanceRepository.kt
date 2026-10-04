package com.example.android.htn.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Cloud Firestore layout (see firebase/firestore.rules for who can do what):
 *
 *   users/{uid}                                  name, email, organization, role, createdAt
 *   events/{eventId}                             name, location, description, startEpochDay, endEpochDay,
 *                                                createdBy, createdAt
 *   events/{eventId}/checkIns/{uid}              userId, checkedInAt, checkedInBy, method
 *   events/{eventId}/registrations/{uid}         userId, registeredAt
 *   events/{eventId}/projects/{uid}              title, description, link, teamMembers, submittedBy,
 *                                                submitterName, createdAt, updatedAt
 *   events/{eventId}/projects/{uid}/scores/{judgeUid}
 *                                                judgeId, innovation, technical, design, impact, comment,
 *                                                updatedAt
 */
class AttendanceRepository(private val db: FirebaseFirestore) {

    private val users = db.collection("users")
    private val events = db.collection("events")

    fun user(uid: String): Flow<UserProfile?> =
        users.document(uid).snapshots().map { it.toUser() }.logErrors(null)

    /** Staff only. */
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
            ref.collection("checkIns").get().await().documents +
            ref.collection("registrations").get().await().documents
        children.chunked(400).forEach { chunk ->
            db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await()
        }
        ref.delete().await()
    }

    /** Staff only. */
    fun allCheckIns(): Flow<List<CheckIn>> =
        db.collectionGroup("checkIns").snapshots()
            .map { s -> s.documents.mapNotNull { it.toCheckIn() } }.logErrors(emptyList())

    /** Staff only. */
    fun allRegistrations(): Flow<List<Registration>> =
        db.collectionGroup("registrations").snapshots()
            .map { s -> s.documents.mapNotNull { it.toRegistration() } }.logErrors(emptyList())

    fun checkInsOf(uid: String): Flow<List<CheckIn>> =
        db.collectionGroup("checkIns").whereEqualTo("userId", uid).snapshots()
            .map { s -> s.documents.mapNotNull { it.toCheckIn() } }.logErrors(emptyList())

    fun registrationsOf(uid: String): Flow<List<Registration>> =
        db.collectionGroup("registrations").whereEqualTo("userId", uid).snapshots()
            .map { s -> s.documents.mapNotNull { it.toRegistration() } }.logErrors(emptyList())

    suspend fun register(eventId: String, uid: String) {
        events.document(eventId).collection("registrations").document(uid)
            .set(mapOf("userId" to uid, "registeredAt" to FieldValue.serverTimestamp())).await()
    }

    suspend fun unregister(eventId: String, uid: String) {
        events.document(eventId).collection("registrations").document(uid).delete().await()
    }

    /** Volunteers and organizers only. Needs a connection, since it checks for an existing check-in. */
    suspend fun checkIn(eventId: String, userId: String, checkedInBy: String, method: CheckInMethod): CheckInResult {
        val userRef = users.document(userId)
        val checkInRef = events.document(eventId).collection("checkIns").document(userId)
        return db.runTransaction { tx ->
            val user = tx.get(userRef).toUser() ?: return@runTransaction CheckInResult.UnknownUser
            if (tx.get(checkInRef).exists()) return@runTransaction CheckInResult.AlreadyCheckedIn(user)
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

    private fun projectRef(eventId: String, submitterId: String) =
        events.document(eventId).collection("projects").document(submitterId)

    /** Judges and organizers only. */
    fun projectsForEvent(eventId: String): Flow<List<Project>> =
        events.document(eventId).collection("projects").snapshots()
            .map { s -> s.documents.mapNotNull { it.toProject() } }.logErrors(emptyList())

    /** Judges and organizers only. */
    fun allProjects(): Flow<List<Project>> =
        db.collectionGroup("projects").snapshots()
            .map { s -> s.documents.mapNotNull { it.toProject() } }.logErrors(emptyList())

    fun projectsOf(uid: String): Flow<List<Project>> =
        db.collectionGroup("projects").whereEqualTo("submittedBy", uid).snapshots()
            .map { s -> s.documents.mapNotNull { it.toProject() } }.logErrors(emptyList())

    fun project(eventId: String, submitterId: String): Flow<Project?> =
        projectRef(eventId, submitterId).snapshots().map { it.toProject() }.logErrors(null)

    /** Creates or updates the signed-in person's project. They must have been checked in. */
    suspend fun saveProject(
        eventId: String,
        me: UserProfile,
        title: String,
        description: String,
        link: String,
        teamMembers: List<String>,
    ) {
        val ref = projectRef(eventId, me.uid)
        val fields = mapOf(
            "title" to title.trim(),
            "description" to description.trim(),
            "link" to link.trim(),
            "teamMembers" to teamMembers.map { it.trim() }.filter { it.isNotEmpty() },
            "submittedBy" to me.uid,
            "submitterName" to me.name,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        db.runTransaction { tx ->
            val existing = tx.get(ref)
            if (existing.exists()) {
                tx.set(ref, fields + ("createdAt" to existing.get("createdAt")))
            } else {
                tx.set(ref, fields + ("createdAt" to FieldValue.serverTimestamp()))
            }
        }.await()
    }

    /**
     * Owners (before the event ends) and organizers. Only organizers can see scores, so they also
     * remove them; scores left behind by an owner's delete are ignored because rankings join on projects.
     */
    suspend fun deleteProject(eventId: String, submitterId: String, asOrganizer: Boolean) {
        val ref = projectRef(eventId, submitterId)
        if (asOrganizer) {
            ref.collection("scores").get().await().documents.forEach { it.reference.delete().await() }
        }
        ref.delete().await()
    }

    /** Organizers only. */
    fun allScores(): Flow<List<Score>> =
        db.collectionGroup("scores").snapshots()
            .map { s -> s.documents.mapNotNull { it.toScore() } }.logErrors(emptyList())

    fun scoresBy(judgeId: String): Flow<List<Score>> =
        db.collectionGroup("scores").whereEqualTo("judgeId", judgeId).snapshots()
            .map { s -> s.documents.mapNotNull { it.toScore() } }.logErrors(emptyList())

    /** Judges only; replaces the judge's previous score for this project. */
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
    val eventId = reference.parent.parent?.id ?: return null
    return Registration(eventId = eventId, userId = getString("userId") ?: return null)
}

private fun DocumentSnapshot.toProject(): Project? {
    if (!exists()) return null
    return Project(
        eventId = reference.parent.parent?.id ?: return null,
        submittedBy = getString("submittedBy") ?: return null,
        submitterName = getString("submitterName").orEmpty(),
        title = getString("title").orEmpty(),
        description = getString("description").orEmpty(),
        link = getString("link").orEmpty(),
        teamMembers = (get("teamMembers") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        updatedAt = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
    )
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
