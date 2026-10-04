package com.example.android.htn.ui.attendees

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Attendee
import com.example.android.htn.profiling.AttendanceProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.rememberRepository
import com.example.android.htn.ui.todayEpochDay
import kotlinx.coroutines.flow.combine

private enum class SortOrder(val label: String) { NAME("Name"), MOST_EVENTS("Most events"), RATE("Attendance rate") }

private data class ProfiledAttendee(val attendee: Attendee, val profile: AttendanceProfile)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendeesScreen(onOpenAttendee: (Long) -> Unit) {
    val repository = rememberRepository()
    val profiledFlow = remember(repository) {
        combine(repository.attendees, repository.events, repository.checkIns) { attendees, events, checkIns ->
            val attendedByAttendee = checkIns.groupBy({ it.attendeeId }, { it.eventId })
            val today = todayEpochDay()
            attendees.map { attendee ->
                val attended = attendedByAttendee[attendee.id].orEmpty().toSet()
                ProfiledAttendee(attendee, AttendanceProfiler.profile(events, attended, today))
            }
        }
    }
    val profiled by profiledFlow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortOrder.NAME) }

    Scaffold(topBar = { TopAppBar(title = { Text("Attendees") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("Search by name, email or school") },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach {
                    FilterChip(selected = sort == it, onClick = { sort = it }, label = { Text(it.label) })
                }
            }

            val all = profiled ?: return@Column
            if (all.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Attendees appear here once they check in to an event.", Modifier.padding(32.dp))
                }
                return@Column
            }

            val q = query.trim()
            val visible = all
                .filter {
                    q.isEmpty() || it.attendee.name.contains(q, ignoreCase = true) ||
                        it.attendee.email.contains(q, ignoreCase = true) ||
                        it.attendee.organization.contains(q, ignoreCase = true)
                }
                .let { list ->
                    when (sort) {
                        SortOrder.NAME -> list
                        SortOrder.MOST_EVENTS -> list.sortedByDescending { it.profile.eventsAttended }
                        SortOrder.RATE -> list.sortedByDescending { it.profile.attendanceRate }
                    }
                }

            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.attendee.id }) { (attendee, profile) ->
                    ListItem(
                        headlineContent = { Text(attendee.name) },
                        supportingContent = {
                            Text(
                                "${profile.eventsAttended} event(s) · " +
                                    "${(profile.attendanceRate * 100).toInt()}% attendance · ${attendee.role.label}"
                            )
                        },
                        trailingContent = { TierBadge(profile.tier) },
                        modifier = Modifier.clickable { onOpenAttendee(attendee.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
