package com.example.android.htn.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.AttendanceProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.components.ProfileStats
import com.example.android.htn.ui.components.RoleBadge
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.components.TimelineRow
import com.example.android.htn.ui.percent
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private enum class SortOrder(val label: String) { NAME("Name"), MOST_EVENTS("Most events"), RATE("Attendance"), NO_SHOWS("No-shows") }

private data class Person(val user: UserProfile, val profile: AttendanceProfile)

/** Organizers only: everyone with an account, profiled by attendance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(onOpenPerson: (String) -> Unit) {
    val repository = rememberApp().repository
    val peopleFlow = remember(repository) {
        combine(repository.users(), repository.events(), repository.allCheckIns(), repository.allRegistrations()) {
                users, events, checkIns, registrations ->
            val today = todayEpochDay()
            users.map { Person(it, AttendanceProfiler.profile(it.uid, events, checkIns, registrations, today)) }
        }
    }
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortOrder.NAME) }
    var roleFilter by rememberSaveable { mutableStateOf<Role?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("People") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("Search by name, email or school") },
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = roleFilter == null, onClick = { roleFilter = null }, label = { Text("Everyone") })
                Role.entries.forEach { role ->
                    FilterChip(selected = roleFilter == role, onClick = { roleFilter = role }, label = { Text(role.label) })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sort:", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelLarge)
                SortOrder.entries.forEach {
                    FilterChip(selected = sort == it, onClick = { sort = it }, label = { Text(it.label) })
                }
            }

            val all = people
            if (all == null) {
                LoadingBox()
                return@Column
            }
            val q = query.trim()
            val visible = all
                .filter { roleFilter == null || it.user.role == roleFilter }
                .filter {
                    q.isEmpty() || it.user.name.contains(q, true) || it.user.email.contains(q, true) ||
                        it.user.organization.contains(q, true)
                }
                .let { list ->
                    when (sort) {
                        SortOrder.NAME -> list.sortedBy { it.user.name.lowercase() }
                        SortOrder.MOST_EVENTS -> list.sortedByDescending { it.profile.eventsAttended }
                        SortOrder.RATE -> list.sortedByDescending { it.profile.attendanceRate }
                        SortOrder.NO_SHOWS -> list.sortedByDescending { it.profile.noShows }
                    }
                }

            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.user.uid }) { (user, profile) ->
                    ListItem(
                        headlineContent = { Text(user.name) },
                        supportingContent = {
                            Text("${user.role.label} · ${profile.eventsAttended} event(s) · " +
                                "${percent(profile.attendanceRate)} attendance" +
                                if (profile.noShows > 0) " · ${profile.noShows} no-show(s)" else "")
                        },
                        trailingContent = { TierBadge(profile.tier) },
                        modifier = Modifier.clickable { onOpenPerson(user.uid) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Organizers only: a person's attendance, projects and role. */
@Composable
fun PersonScreen(
    uid: String,
    me: UserProfile,
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenProject: (eventId: String, projectId: String) -> Unit,
) {
    val repository = rememberApp().repository
    val dataFlow = remember(uid) {
        combine(repository.user(uid), repository.events(), repository.checkInsOf(uid), repository.registrationsOf(uid)) {
                user, events, checkIns, registrations ->
            user?.let { it to AttendanceProfiler.profile(uid, events, checkIns, registrations, todayEpochDay()) }
        }
    }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    val projectsFlow = remember(uid) {
        combine(repository.events(), repository.projectsOf(uid)) { events, projects ->
            val byId = events.associateBy { it.id }
            projects.mapNotNull { p -> byId[p.eventId]?.let { it to p } }.sortedByDescending { it.first.startEpochDay }
        }
    }
    val projects by projectsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = { BackTopBar(data?.first?.name.orEmpty(), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val (user, profile) = data ?: run {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(user.email, style = MaterialTheme.typography.bodyLarge)
                    if (user.organization.isNotBlank()) Text(user.organization)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoleBadge(user.role)
                        TierBadge(profile.tier)
                    }
                    if (me.role.canManageEvents && me.uid != user.uid) {
                        Text("Change role", style = MaterialTheme.typography.titleSmall)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Role.entries.forEach { role ->
                                FilterChip(
                                    selected = user.role == role,
                                    onClick = {
                                        if (role != user.role) scope.launch {
                                            try {
                                                repository.setRole(user.uid, role)
                                                snackbar.showSnackbar("${user.name} is now a ${role.label.lowercase()}")
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar(e.userMessage())
                                            }
                                        }
                                    },
                                    label = { Text(role.label) },
                                )
                            }
                        }
                    }
                    ProfileStats(profile)
                    if (projects.isNotEmpty()) {
                        Text("Projects", style = MaterialTheme.typography.titleMedium)
                        projects.forEach { (event, project) ->
                            ListItem(
                                headlineContent = { Text(project.title) },
                                supportingContent = { Text("${event.name} · ${project.teamLabel}") },
                                modifier = Modifier.clickable { onOpenProject(event.id, project.id) },
                            )
                        }
                    }
                    Text("History", style = MaterialTheme.typography.titleMedium)
                    if (profile.timeline.isEmpty()) Text("No past events yet.")
                }
            }
            items(profile.timeline, key = { it.event.id }) { TimelineRow(it) { onOpenEvent(it.event.id) } }
        }
    }
}
