package com.example.android.htn

import android.app.Application
import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.AuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AttendanceApp : Application() {

    /** False when the app was built without app/google-services.json. */
    val firebaseConfigured: Boolean by lazy {
        FirebaseApp.getApps(this).isNotEmpty() || FirebaseApp.initializeApp(this) != null
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
    }

    val repository: AttendanceRepository by lazy {
        AttendanceRepository(FirebaseFirestore.getInstance())
    }
}
