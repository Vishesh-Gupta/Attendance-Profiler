package com.example.android.htn

import android.app.Application
import com.example.android.htn.data.AttendanceDatabase
import com.example.android.htn.data.AttendanceRepository

class AttendanceApp : Application() {
    val repository: AttendanceRepository by lazy {
        AttendanceRepository(AttendanceDatabase.create(this))
    }
}
