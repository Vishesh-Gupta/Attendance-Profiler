package com.example.android.htn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTest {

    private val htn = Event("htn", "Hack the North", "Waterloo", startEpochDay = 100, endEpochDay = 102)

    @Test
    fun teamsLockFourteenDaysBeforeTheStart() {
        assertEquals(86, htn.teamDeadlineEpochDay)
        assertTrue(htn.teamsOpen(85))
        assertTrue(htn.teamsOpen(86)) // the deadline day itself is still open
        assertFalse(htn.teamsOpen(87))
        assertFalse(htn.teamsOpen(101))
    }

    @Test
    fun overOnlyAfterTheLastDay() {
        assertFalse(htn.isOver(102))
        assertTrue(htn.isOver(103))
    }
}
