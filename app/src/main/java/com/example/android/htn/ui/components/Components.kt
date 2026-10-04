package com.example.android.htn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.android.htn.data.Role
import com.example.android.htn.profiling.AttendanceProfile
import com.example.android.htn.profiling.AttendanceTier
import com.example.android.htn.profiling.TimelineEntry
import com.example.android.htn.profiling.TimelineStatus
import com.example.android.htn.ui.formatEpochDay
import com.example.android.htn.ui.percent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun TierBadge(tier: AttendanceTier) {
    val color = when (tier) {
        AttendanceTier.NONE -> MaterialTheme.colorScheme.surfaceVariant
        AttendanceTier.FIRST_TIMER -> MaterialTheme.colorScheme.secondaryContainer
        AttendanceTier.RETURNING -> MaterialTheme.colorScheme.primaryContainer
        AttendanceTier.VETERAN -> MaterialTheme.colorScheme.tertiaryContainer
    }
    AssistChip(
        onClick = {},
        label = { Text(tier.label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color),
    )
}

@Composable
fun RoleBadge(role: Role) {
    AssistChip(onClick = {}, label = { Text(role.label) })
}

/** Stat cards for an attendance profile. Pair with [timelineItem] rows for the history. */
@Composable
fun ProfileStats(profile: AttendanceProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Events attended", "${profile.eventsAttended}", Modifier.weight(1f))
            StatCard("Attendance", percent(profile.attendanceRate), Modifier.weight(1f))
            StatCard("No-shows", "${profile.noShows}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Current streak", "${profile.currentStreak}", Modifier.weight(1f))
            StatCard("Longest streak", "${profile.longestStreak}", Modifier.weight(1f))
        }
        profile.firstSeen?.let { Text("First seen at ${it.name} (${formatEpochDay(it.startEpochDay)})") }
        profile.lastSeen?.let { Text("Last seen at ${it.name} (${formatEpochDay(it.startEpochDay)})") }
    }
}

@Composable
fun TimelineRow(entry: TimelineEntry, onClick: () -> Unit) {
    val (icon, label, tint) = when (entry.status) {
        TimelineStatus.ATTENDED -> Triple(Icons.Filled.Check, "Attended", MaterialTheme.colorScheme.primary)
        TimelineStatus.NO_SHOW -> Triple(Icons.Filled.Warning, "Registered, didn't attend", MaterialTheme.colorScheme.error)
        TimelineStatus.MISSED -> Triple(Icons.Filled.Close, "Didn't attend", MaterialTheme.colorScheme.outline)
    }
    ListItem(
        headlineContent = { Text(entry.event.name) },
        supportingContent = { Text("${formatEpochDay(entry.event.startEpochDay)} · $label") },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}
