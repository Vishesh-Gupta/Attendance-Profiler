package com.example.android.htn.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class AuthUser(val uid: String, val email: String)

class AuthRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
) {
    val authUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.let { AuthUser(it.uid, it.email.orEmpty()) })
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * Creates the account and its participant profile. Roles are assigned later by an organizer.
     * Non-cancellable because signing in swaps out the sign-up screen that launched this.
     */
    suspend fun signUp(name: String, email: String, password: String, organization: String) =
        withContext(NonCancellable) {
            val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
                ?: error("Sign-up failed")
            createProfile(user.uid, user.email.orEmpty(), name, organization)
        }

    /** Also used to finish setup if sign-up was interrupted after the account was created. */
    suspend fun createProfile(uid: String, email: String, name: String, organization: String) {
        db.collection("users").document(uid).set(
            mapOf(
                "name" to name.trim(),
                "email" to email,
                "organization" to organization.trim(),
                "role" to Role.PARTICIPANT.name,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() = auth.signOut()
}
