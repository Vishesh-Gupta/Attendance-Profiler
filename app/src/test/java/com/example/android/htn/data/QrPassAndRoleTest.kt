package com.example.android.htn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPassAndRoleTest {

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
    fun rolePermissionsMatchFirestoreRules() {
        fun perms(role: Role) = listOf(
            role.canCheckIn, role.canSeeEveryone, role.canManageEvents, role.canJudge,
        )
        //                                       checkIn everyone manage  judge
        assertEquals(listOf(false, false, false, false), perms(Role.PARTICIPANT))
        assertEquals(listOf(false, false, false, true), perms(Role.JUDGE))
        assertEquals(listOf(true, false, false, false), perms(Role.VOLUNTEER))
        assertEquals(listOf(true, true, true, false), perms(Role.ORGANIZER))
    }

    @Test
    fun unknownRolesFallBackToParticipant() {
        assertEquals(Role.PARTICIPANT, Role.parse("SUPERUSER"))
        assertEquals(Role.PARTICIPANT, Role.parse(null))
        assertEquals(Role.JUDGE, Role.parse("JUDGE"))
    }
}
