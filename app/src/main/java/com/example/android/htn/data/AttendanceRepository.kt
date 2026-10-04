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
 *   events/{eventId}                             name, location, startEpochDay, createdBy, createdAt
 *   events/{eventId}/checkIns/{uid}              userId, checkedInAt, checkedInBy, method
 *   events/{eventId}/registrations/{uid}         userId, registeredAt
 */
class AttendanceRepository(private val db: FirebaseFirestore) {

    private val users = db.collection("users")
    private val events = db.collection("events")

    fun user(uid: String): Flow<UserProfile?> =
        users.document(uid).snapshots().map { it.toUser() }.logErrors(null)

    /** Staff only. */
    fun users(): Flow<List<UserProfile>> =
        users.orderBy("name").snapshots().map { s -> s.documents.mapNotNull { it.toUser() } }.logErrors(emptyList())

    suspend fun setRole(uid: String, role: Role) {
        users.document(uid).update("role", role.name).await()
    }

    fun events(): Flow<List<Event>> =
        events.orderBy("startEpochDay", Query.Direction.DESCENDING).snapshots()
            .map { s -> s.documents.mapNotNull { it.toEvent() } }.logErrors(emptyList())

    fun event(eventId: String): Flow<Event?> =
        events.document(eventId).snapshots().map { it.toEvent() }.logErrors(null)

    suspend fun createEvent(name: String, location: String, startEpochDay: Long, createdBy: String): String {
        val ref = events.document()
        ref.set(
            mapOf(
                "name" to name.trim(),
                "location" to location.trim(),
                "startEpochDay" to startEpochDay,
                "createdBy" to createdBy,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        return ref.id
    }

    /** Firestore doesn't delete subcollections with their parent, so remove them first. */
    suspend fun deleteEvent(eventId: String) {
        val ref = events.document(eventId)
        for (sub in listOf("checkIns", "registrations")) {
            ref.collection(sub).get().await().documents.chunked(400).forEach { chunk ->
                db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await()
            }
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
    return Event(
        id = id,
        name = getString("name").orEmpty(),
        location = getString("location").orEmpty(),
        startEpochDay = getLong("startEpochDay") ?: return null,
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
