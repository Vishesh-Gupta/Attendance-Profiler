package com.example.android.htn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.android.htn.AttendanceApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun rememberApp(): AttendanceApp = LocalContext.current.applicationContext as AttendanceApp

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)

fun percent(rate: Double): String = "${Math.round(rate * 100)}%"

/** Firebase exception messages are user-readable; fall back to something generic otherwise. */
fun Throwable.userMessage(): String = localizedMessage?.takeIf { it.isNotBlank() } ?: "Something went wrong"

/** "Sep 11, 2026" for one-day events, "Sep 11, 2026 – Sep 13, 2026" for longer ones. */
fun formatEventDates(event: com.example.android.htn.data.Event): String =
    if (event.endEpochDay == event.startEpochDay) formatEpochDay(event.startEpochDay)
    else "${formatEpochDay(event.startEpochDay)} – ${formatEpochDay(event.endEpochDay)}"
