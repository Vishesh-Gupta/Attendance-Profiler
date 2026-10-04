package com.example.android.htn.ui.profile

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.QrPass
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.AttendanceProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.profiling.Judging
import com.example.android.htn.ui.components.ProfileStats
import com.example.android.htn.ui.components.QrCode
import com.example.android.htn.ui.components.RoleBadge
import com.example.android.htn.ui.components.StatCard
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.components.TimelineRow
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Role-specific numbers shown on the profile; each role only loads data it is allowed to read. */
private data class RoleStats(val cards: List<Pair<String, String>>, val action: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(me: UserProfile, onOpenEvent: (String) -> Unit, onOpenTab: (String) -> Unit) {
    val app = rememberApp()
    val repository = app.repository
    val attendanceFlow = remember(me.uid) {
        combine(repository.events(), repository.checkInsOf(me.uid), repository.registrationsOf(me.uid)) { events, checkIns, regs ->
            AttendanceProfiler.profile(me.uid, events, checkIns, regs, todayEpochDay())
        }
    }
    val attendance by attendanceFlow.collectAsStateWithLifecycle(initialValue = null)
    val roleStatsFlow = remember(me.uid, me.role) { roleStats(repository, me) }
    val roleStats by roleStatsFlow.collectAsStateWithLifecycle(initialValue = null)
    var showPass by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit profile") }
                    IconButton(onClick = { app.authRepository.signOut() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(me.name, style = MaterialTheme.typography.headlineSmall)
                    Text(me.email)
                    if (me.organization.isNotBlank()) Text(me.organization)
                    RoleBadge(me.role)
                    Button(onClick = { showPass = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Show my check-in pass")
                    }
                }
            }
            roleStats?.let { stats -> item { RoleSection(me.role, stats, onOpenTab) } }
            attendance?.let { profile -> attendanceSection(profile, onOpenEvent) }
        }
    }

    if (showPass) PassDialog(me, onDismiss = { showPass = false })
    if (editing) {
        EditProfileDialog(
            me,
            onDismiss = { editing = false },
            onSave = { name, org ->
                editing = false
                scope.launch {
                    try { repository.updateProfile(me.uid, name, org) }
                    catch (e: Exception) { snackbar.showSnackbar(e.userMessage()) }
                }
            },
        )
    }
}

private fun roleStats(repository: AttendanceRepository, me: UserProfile): Flow<RoleStats> =
    when (me.role) {
        Role.PARTICIPANT -> repository.projectsOf(me.uid).map {
            RoleStats(listOf("Projects submitted" to "${it.size}"), action = "projects")
        }
        Role.JUDGE -> combine(repository.allProjects(), repository.scoresBy(me.uid)) { projects, scores ->
            val progress = Judging.progress(me.uid, projects, scores)
            RoleStats(listOf("Scored" to "${progress.scored}", "Left to score" to "${progress.remaining}"), action = "projects")
        }
        Role.VOLUNTEER -> repository.allCheckIns().map { checkIns ->
            val mine = checkIns.filter { it.checkedInBy == me.uid }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val todayCount = mine.count { java.time.Instant.ofEpochMilli(it.checkedInAt).atZone(zone).toLocalDate() == today }
            RoleStats(listOf("People you checked in" to "${mine.size}", "Today" to "$todayCount"), action = "events")
        }
        Role.ORGANIZER -> combine(repository.users(), repository.events(), repository.allCheckIns(), repository.allProjects()) {
                users, events, checkIns, projects ->
            val today = todayEpochDay()
            val byRole = users.groupingBy { it.role }.eachCount()
            RoleStats(
                listOf(
                    "People" to "${users.size}",
                    "Participants" to "${byRole[Role.PARTICIPANT] ?: 0}",
                    "Judges" to "${byRole[Role.JUDGE] ?: 0}",
                    "Volunteers" to "${byRole[Role.VOLUNTEER] ?: 0}",
                    "Upcoming events" to "${events.count { !it.isOver(today) }}",
                    "Check-ins" to "${checkIns.size}",
                    "Projects" to "${projects.size}",
                ),
                action = "people",
            )
        }
    }

@Composable
private fun RoleSection(role: Role, stats: RoleStats, onOpenTab: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (role) {
                Role.PARTICIPANT -> "Your projects"
                Role.JUDGE -> "Your judging"
                Role.VOLUNTEER -> "Your check-in shifts"
                Role.ORGANIZER -> "Overview"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        stats.cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
                repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
        stats.action?.let { route ->
            OutlinedButton(onClick = { onOpenTab(route) }) {
                Text(
                    when (role) {
                        Role.PARTICIPANT -> "My projects"
                        Role.JUDGE -> "Go to judging"
                        Role.VOLUNTEER -> "Open events to check people in"
                        Role.ORGANIZER -> "See everyone"
                    }
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.attendanceSection(
    profile: AttendanceProfile,
    onOpenEvent: (String) -> Unit,
) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your attendance", style = MaterialTheme.typography.titleMedium)
            TierBadge(profile.tier)
            ProfileStats(profile)
        }
    }
    items(profile.timeline, key = { it.event.id }) { TimelineRow(it) { onOpenEvent(it.event.id) } }
}

@Composable
private fun PassDialog(me: UserProfile, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        MaxBrightness()
        Card {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QrCode(
                    content = QrPass.encode(me.uid),
                    contentDescription = "Check-in QR code for ${me.name}",
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                )
                Text(me.name, style = MaterialTheme.typography.titleLarge)
                Text(me.role.label)
                Text(
                    "Show this to a volunteer or organizer to check in.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

/** Scanners read a bright screen much more reliably. Applies to the dialog's own window. */
@Composable
private fun MaxBrightness() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

@Composable
private fun EditProfileDialog(me: UserProfile, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(me.name) }
    var organization by remember { mutableStateOf(me.organization) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
                OutlinedTextField(organization, { organization = it }, label = { Text("School / company") }, singleLine = true)
                Text("Only an organizer can change your role.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name.length <= 100 && organization.length <= 100,
                onClick = { onSave(name, organization) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
