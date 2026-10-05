package com.example.android.htn.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.android.htn.data.Event
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

/** Month grid with a dot under each day that has events. Tapping a day selects it. */
@Composable
fun EventCalendar(
    month: YearMonth,
    events: List<Event>,
    selected: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = remember(month, events) { CalendarModel.weeks(month, events) }
    val today = LocalDate.now()

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                month.format(monthTitle),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        if (YearMonth.from(today) != month || selected != today) {
            TextButton(
                onClick = { onMonthChange(YearMonth.from(today)); onSelect(today) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Today") }
        }
        Row(Modifier.fillMaxWidth()) {
            CalendarModel.weekdayOrder().forEach {
                Text(
                    it.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        date = day.date,
                        inMonth = day.inMonth,
                        eventCount = day.events.size,
                        isToday = day.date == today,
                        isSelected = day.date == selected,
                        onClick = { onSelect(day.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    eventCount: Int,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = buildString {
        append(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
        if (eventCount > 0) append(", $eventCount event${if (eventCount > 1) "s" else ""}")
    }
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(colors.primary) else Modifier)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "${date.dayOfMonth}",
                color = when {
                    isSelected -> colors.onPrimary
                    !inMonth -> colors.outline
                    else -> colors.onSurface
                },
                fontWeight = if (eventCount > 0) FontWeight.Bold else FontWeight.Normal,
            )
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            eventCount == 0 -> androidx.compose.ui.graphics.Color.Transparent
                            isSelected -> colors.onPrimary
                            else -> colors.tertiary
                        }
                    )
            )
            Box(Modifier.height(2.dp))
        }
    }
}
