package com.example.android.htn.ui.attendees

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.StatCard
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.formatEpochDay
import com.example.android.htn.ui.rememberRepository
import com.example.android.htn.ui.todayEpochDay
import kotlinx.coroutines.flow.combine

@Composable
fun AttendeeProfileScreen(attendeeId: Long, onBack: () -> Unit, onOpenEvent: (Long) -> Unit) {
    val repository = rememberRepository()
    val dataFlow = remember(repository, attendeeId) {
        combine(repository.attendee(attendeeId), repository.events, repository.checkIns) { attendee, events, checkIns ->
            attendee?.let {
                val attended = checkIns.filter { c -> c.attendeeId == attendeeId }.map { c -> c.eventId }.toSet()
                it to AttendanceProfiler.profile(events, attended, todayEpochDay())
            }
        }
    }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(topBar = { BackTopBar(data?.first?.name ?: "", onBack) }) { padding ->
        val (attendee, profile) = data ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(attendee.email, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(attendee.role.label, attendee.organization).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TierBadge(profile.tier)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("Events attended", "${profile.eventsAttended}", Modifier.weight(1f))
                        StatCard("Attendance", "${(profile.attendanceRate * 100).toInt()}%", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("Current streak", "${profile.currentStreak}", Modifier.weight(1f))
                        StatCard("Longest streak", "${profile.longestStreak}", Modifier.weight(1f))
                    }
                    profile.firstSeen?.let {
                        Text("First seen at ${it.name} (${formatEpochDay(it.startEpochDay)})")
                    }
                    profile.lastSeen?.let {
                        Text("Last seen at ${it.name} (${formatEpochDay(it.startEpochDay)})")
                    }
                    Text("History", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(profile.timeline, key = { it.event.id }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.event.name) },
                    supportingContent = { Text(formatEpochDay(entry.event.startEpochDay)) },
                    leadingContent = {
                        if (entry.attended) {
                            Icon(Icons.Filled.Check, contentDescription = "Attended",
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Filled.Close, contentDescription = "Missed",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.clickable { onOpenEvent(entry.event.id) },
                )
                HorizontalDivider()
            }
        }
    }
}
