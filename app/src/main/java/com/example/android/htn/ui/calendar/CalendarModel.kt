package com.example.android.htn.ui.calendar

import com.example.android.htn.data.Event
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class CalendarDay(
    val date: LocalDate,
    /** False for the leading/trailing days borrowed from neighbouring months. */
    val inMonth: Boolean,
    val events: List<Event>,
)

/** A month laid out as full weeks (rows of 7), including multi-day events on every day they span. */
object CalendarModel {

    fun weeks(month: YearMonth, events: List<Event>, firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): List<List<CalendarDay>> {
        val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(firstDayOfWeek.minus(1)))
        val sorted = events.sortedWith(compareBy({ it.startEpochDay }, { it.name }))
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .map { date ->
                val day = date.toEpochDay()
                CalendarDay(date, YearMonth.from(date) == month, sorted.filter { it.isOn(day) })
            }
            .chunked(7)
            .toList()
    }

    fun weekdayOrder(firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): List<DayOfWeek> =
        (0L until 7L).map { firstDayOfWeek.plus(it) }
}
