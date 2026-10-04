package com.example.android.htn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Event::class, Attendee::class, CheckIn::class], version = 1)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        fun create(context: Context): AttendanceDatabase =
            Room.databaseBuilder(context, AttendanceDatabase::class.java, "attendance.db").build()
    }
}
