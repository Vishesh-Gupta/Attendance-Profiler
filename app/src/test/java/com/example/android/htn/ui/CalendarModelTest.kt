package com.example.android.htn.ui

import com.example.android.htn.data.Event
import com.example.android.htn.ui.calendar.CalendarModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarModelTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test
    fun monthIsPaddedToWholeWeeks() {
        // September 2026 starts on a Tuesday and ends on a Wednesday.
        val weeks = CalendarModel.weeks(YearMonth.of(2026, 9), emptyList())
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(LocalDate.of(2026, 8, 30), weeks.first().first().date) // Sunday before
        assertEquals(LocalDate.of(2026, 10, 3), weeks.last().last().date) // Saturday after
        assertEquals(30, weeks.flatten().count { it.inMonth })
    }

    @Test
    fun mondayFirstWeeks() {
        val weeks = CalendarModel.weeks(YearMonth.of(2026, 9), emptyList(), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 8, 31), weeks.first().first().date)
        assertEquals(DayOfWeek.SUNDAY, weeks.last().last().date.dayOfWeek)
    }

    @Test
    fun multiDayEventsAppearOnEveryDayTheySpan() {
        val htn = Event("htn", "Hack the North", "Waterloo", day(2026, 9, 11), day(2026, 9, 13))
        val talk = Event("talk", "Talk", "", day(2026, 9, 12))
        val days = CalendarModel.weeks(YearMonth.of(2026, 9), listOf(talk, htn)).flatten().associateBy { it.date }

        assertEquals(listOf("htn"), days.getValue(LocalDate.of(2026, 9, 11)).events.map { it.id })
        assertEquals(listOf("htn", "talk"), days.getValue(LocalDate.of(2026, 9, 12)).events.map { it.id })
        assertEquals(listOf("htn"), days.getValue(LocalDate.of(2026, 9, 13)).events.map { it.id })
        assertTrue(days.getValue(LocalDate.of(2026, 9, 14)).events.isEmpty())
    }

    @Test
    fun eventsSpanningMonthBoundaryShowInBothMonths() {
        val event = Event("x", "X", "", day(2026, 9, 30), day(2026, 10, 2))
        val october = CalendarModel.weeks(YearMonth.of(2026, 10), listOf(event)).flatten()
        assertEquals(2, october.count { it.inMonth && it.events.isNotEmpty() })
    }
}
