package com.example.android.htn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamCodeTest {

    @Test
    fun generatedCodesAreValidAndVaried() {
        val codes = (1..200).map { TeamCode.generate() }
        assertTrue(codes.all { TeamCode.normalize(it) == it })
        assertTrue(codes.toSet().size > 195)
    }

    @Test
    fun normalizeAcceptsWhatPeopleType() {
        assertEquals("ABCDEFGHJK", TeamCode.normalize("abcde-fghjk"))
        assertEquals("ABCDEFGHJK", TeamCode.normalize(" ABCDE FGHJK "))
        assertNull(TeamCode.normalize("ABCDE"))
        assertNull(TeamCode.normalize("ABCDEFGHI0")) // I and 0 aren't in the alphabet
        assertNull(TeamCode.normalize(""))
    }

    @Test
    fun displayInsertsDash() {
        assertEquals("ABCDE-FGHJK", TeamCode.display("ABCDEFGHJK"))
    }

    @Test
    fun qrRoundTrip() {
        val payload = TeamCode.qrPayload("event_123", "ABCDEFGHJK")
        assertEquals("event_123" to "ABCDEFGHJK", TeamCode.decodeQr(payload))
    }

    @Test
    fun qrRejectsOtherCodes() {
        assertNull(TeamCode.decodeQr(QrPass.encode("someUid"))) // a person's pass isn't a team code
        assertNull(TeamCode.decodeQr("attendance-profiler:team:v1:event:short"))
        assertNull(TeamCode.decodeQr("attendance-profiler:team:v1::ABCDEFGHJK"))
        assertNull(TeamCode.decodeQr("https://example.com"))
        assertNotNull(TeamCode.decodeQr("attendance-profiler:team:v1:e:abcdefghjk"))
    }

    @Test
    fun personalPassesAreNotTeamCodes() {
        assertNull(QrPass.decode(TeamCode.qrPayload("event", "ABCDEFGHJK")))
    }
}
