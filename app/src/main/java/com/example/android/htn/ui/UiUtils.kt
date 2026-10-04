package com.example.android.htn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.android.htn.AttendanceApp
import com.example.android.htn.data.AttendanceRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun rememberRepository(): AttendanceRepository =
    (LocalContext.current.applicationContext as AttendanceApp).repository

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)
