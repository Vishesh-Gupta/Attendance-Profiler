package com.example.android.htn.ui

import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.AuthRepository
import com.example.android.htn.data.AuthUser
import com.example.android.htn.data.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    /** Signed in, but the users/{uid} profile doesn't exist yet (e.g. sign-up was interrupted). */
    data class NeedsProfile(val user: AuthUser) : SessionState
    data class SignedIn(val profile: UserProfile) : SessionState
}

private const val PROFILE_GRACE_MILLIS = 3_000L

@OptIn(ExperimentalCoroutinesApi::class)
fun sessionState(auth: AuthRepository, repository: AttendanceRepository): Flow<SessionState> =
    auth.authUser.flatMapLatest { user ->
        if (user == null) {
            flowOf<SessionState>(SessionState.SignedOut)
        } else {
            repository.user(user.uid).transformLatest<UserProfile?, SessionState> { profile ->
                if (profile != null) {
                    emit(SessionState.SignedIn(profile))
                } else {
                    // Right after sign-up the profile is still being written; don't flash the setup form.
                    emit(SessionState.Loading)
                    delay(PROFILE_GRACE_MILLIS)
                    emit(SessionState.NeedsProfile(user))
                }
            }
        }
    }
