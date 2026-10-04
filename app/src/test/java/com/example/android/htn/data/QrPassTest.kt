package com.example.android.htn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPassTest {

    @Test
    fun roundTrip() {
        val uid = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4"
        assertEquals(uid, QrPass.decode(QrPass.encode(uid)))
        assertEquals(uid, QrPass.decode("  ${QrPass.encode(uid)}\n"))
    }

    @Test
    fun rejectsForeignOrMalformedCodes() {
        assertNull(QrPass.decode(null))
        assertNull(QrPass.decode(""))
        assertNull(QrPass.decode("https://example.com"))
        assertNull(QrPass.decode("a1B2c3D4e5F6")) // bare uid without our prefix
        assertNull(QrPass.decode("attendance-profiler:v1:"))
        assertNull(QrPass.decode("attendance-profiler:v1:../users/x"))
        assertNull(QrPass.decode("attendance-profiler:v1:abc def"))
    }

    @Test
    fun rolePermissions() {
        assertFalse(Role.PARTICIPANT.isStaff)
        assertTrue(Role.JUDGE.isStaff)
        assertFalse(Role.JUDGE.canCheckIn)
        assertTrue(Role.VOLUNTEER.canCheckIn)
        assertFalse(Role.VOLUNTEER.canManageEvents)
        assertTrue(Role.ORGANIZER.canManageEvents)
        assertEquals(Role.PARTICIPANT, Role.parse("SUPERUSER"))
        assertEquals(Role.JUDGE, Role.parse("JUDGE"))
    }
}
