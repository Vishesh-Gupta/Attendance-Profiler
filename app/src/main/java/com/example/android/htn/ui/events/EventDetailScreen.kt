package com.example.android.htn.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.android.htn.data.CheckInMethod
import com.example.android.htn.data.CheckInResult
import com.example.android.htn.data.Project
import com.example.android.htn.data.RegistrationStatus
import com.example.android.htn.data.Role
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.components.StatCard
import com.example.android.htn.ui.components.rememberQrScanner
import com.example.android.htn.ui.formatEventDates
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import java.text.DateFormat
import java.util.Date

@Composable
fun EventDetailScreen(
    eventId: String,
    me: UserProfile,
    onBack: () -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onOpenTeam: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenApplications: () -> Unit,
) {
    val repository = rememberApp().repository
    val vm: EventDetailViewModel = viewModel(
        key = "event-$eventId-${me.role}",
        factory = viewModelFactory { initializer { EventDetailViewModel(eventId, me, repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val lastScan by vm.lastScan.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    val scan = rememberQrScanner(onScanned = vm::onScanned, onError = vm::onScanFailed)

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            BackTopBar(state.event?.name.orEmpty(), onBack) {
                if (me.role.canManageEvents && state.event != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete event")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val event = state.event
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        if (event == null) {
            Text("This event no longer exists.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        listOf(formatEventDates(event), event.location)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (event.description.isNotBlank()) Text(event.description)
                    val eventOver = event.isOver(todayEpochDay())
                    MyStatusCard(
                        status = state.myStatus,
                        checkedIn = state.checkedIn,
                        upcoming = !eventOver,
                        onSetRegistered = vm::setRegistered,
                    )
                    if (me.role.canSeeEveryone) {
                        ApplicationsCard(state.applications.values.groupingBy { it }.eachCount(), onOpenApplications)
                    }
                    if (state.resultsPublished) {
                        Button(onClick = onOpenResults, modifier = Modifier.fillMaxWidth()) { Text("See results") }
                    }
                    if (me.role == Role.PARTICIPANT) {
                        TeamCard(
                            state.myTeam,
                            approved = state.myStatus == RegistrationStatus.APPROVED,
                            eventOver = eventOver,
                            onOpen = onOpenTeam,
                        )
                    }
                    if (me.role.canSeeEveryone || me.role.canJudge) {
                        ProjectsCard(state.projectCount, me.role, onOpenProjects)
                    }
                    if (me.role.canCheckIn) {
                        CheckInDesk(
                            lastScan,
                            canApprove = me.role.canSeeEveryone,
                            onScan = scan,
                            onDismiss = vm::dismissScan,
                            onApproveAndCheckIn = vm::approveAndCheckIn,
                        )
                    }
                }
            }
            if (me.role.canCheckIn) manualCheckIn(state, canApprove = me.role.canSeeEveryone, vm)
            if (me.role.canCheckIn) attendance(state, me, vm, onOpenPerson)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text("All registrations and check-ins for this event will be removed for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteEvent(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MyStatusCard(
    status: RegistrationStatus?,
    checkedIn: Boolean,
    upcoming: Boolean,
    onSetRegistered: (Boolean) -> Unit,
) {
    var confirmWithdraw by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                checkedIn -> Text("You're checked in.", style = MaterialTheme.typography.titleMedium)
                !upcoming -> Text(
                    if (status == RegistrationStatus.APPROVED) "You were approved but weren't checked in."
                    else "You didn't attend this event."
                )
                status == null -> {
                    Text("Want to attend? Apply, and organizers will email you once they've reviewed your application.")
                    Button(onClick = { onSetRegistered(true) }) { Text("Apply to attend") }
                }
                else -> {
                    Text(
                        when (status) {
                            RegistrationStatus.PENDING -> "Application received"
                            RegistrationStatus.APPROVED -> "You're approved!"
                            RegistrationStatus.WAITLISTED -> "You're on the waitlist"
                            RegistrationStatus.DECLINED -> "Application not accepted"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when (status) {
                            RegistrationStatus.PENDING -> "Organizers are reviewing applications. We'll email you with their decision."
                            RegistrationStatus.APPROVED -> "Form your team below, and show your pass at check-in."
                            RegistrationStatus.WAITLISTED -> "We'll email you if a spot opens up."
                            RegistrationStatus.DECLINED -> "Unfortunately there isn't a spot for you at this event."
                        }
                    )
                    if (status != RegistrationStatus.DECLINED) {
                        OutlinedButton(onClick = { confirmWithdraw = true }) { Text("Withdraw application") }
                    }
                }
            }
        }
    }
    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw application?") },
            text = { Text("If you apply again, you'll go back to the review queue.") },
            confirmButton = {
                TextButton(onClick = { confirmWithdraw = false; onSetRegistered(false) }) { Text("Withdraw") }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ApplicationsCard(counts: Map<RegistrationStatus, Int>, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Applications", style = MaterialTheme.typography.titleMedium)
            Text(RegistrationStatus.entries.joinToString(" · ") { "${counts[it] ?: 0} ${it.label.lowercase()}" })
            Button(onClick = onOpen) {
                val pending = counts[RegistrationStatus.PENDING] ?: 0
                Text(if (pending > 0) "Review $pending pending" else "Review applications")
            }
        }
    }
}

@Composable
private fun TeamCard(team: Project?, approved: Boolean, eventOver: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your team", style = MaterialTheme.typography.titleMedium)
            when {
                team != null -> {
                    Text(team.title, style = MaterialTheme.typography.bodyLarge)
                    Text(team.teamLabel, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpen) { Text(if (eventOver) "View project" else "Manage, edit or switch team") }
                }
                eventOver -> Text("You didn't submit a project for this event.")
                !approved -> Text("Once your application is approved, you can start a team project or join your teammates'. No need to wait for check-in.")
                else -> {
                    Text("Start a team project, or join your teammates with their team code.")
                    Button(onClick = onOpen) { Text("Create or join a team") }
                }
            }
        }
    }
}

@Composable
private fun ProjectsCard(count: Int, role: Role, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Projects", style = MaterialTheme.typography.titleMedium)
            Text(if (role.canJudge) "$count assigned to you" else "$count submitted")
            Button(onClick = onOpen, enabled = count > 0) {
                Text(if (role.canJudge) "Judge projects" else "Judges, leaderboard & results")
            }
        }
    }
}

@Composable
private fun CheckInDesk(
    lastScan: ScanOutcome?,
    canApprove: Boolean,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onApproveAndCheckIn: (UserProfile, CheckInMethod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check-in desk", style = MaterialTheme.typography.titleMedium)
        lastScan?.let { ScanResultCard(it, canApprove, onDismiss, onApproveAndCheckIn) }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text(if (lastScan == null) "Scan QR pass" else "Scan next")
        }
    }
}

@Composable
private fun ScanResultCard(
    outcome: ScanOutcome,
    canApprove: Boolean,
    onDismiss: () -> Unit,
    onApproveAndCheckIn: (UserProfile, CheckInMethod) -> Unit,
) {
    val notApproved = (outcome as? ScanOutcome.Done)?.let { done ->
        (done.result as? CheckInResult.NotApproved)?.let { it to done.method }
    }
    val (color, title, detail) = when (outcome) {
        is ScanOutcome.Done -> when (val r = outcome.result) {
            is CheckInResult.CheckedIn -> Triple(Color(0xFF2E7D32), "Checked in: ${r.user.name}", r.user.role.label)
            is CheckInResult.AlreadyCheckedIn -> Triple(Color(0xFFF9A825), "Already checked in: ${r.user.name}", r.user.role.label)
            is CheckInResult.NotApproved -> Triple(
                Color(0xFFC62828),
                "Not approved: ${r.user.name}",
                (r.status?.let { "Application: ${it.label.lowercase()}." } ?: "They never applied.") +
                    if (canApprove) "" else " Ask an organizer.",
            )
            CheckInResult.UnknownUser -> Triple(Color(0xFFC62828), "Unknown person", "This pass doesn't match any account.")
        }
        ScanOutcome.NotAPass -> Triple(Color(0xFFC62828), "Not a check-in pass", "Ask them to open My pass in the app.")
        is ScanOutcome.Failed -> Triple(Color(0xFFC62828), "Check-in failed", outcome.message)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.White),
    ) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail)
                if (canApprove && notApproved != null) {
                    val (result, method) = notApproved
                    Button(
                        onClick = { onApproveAndCheckIn(result.user, method) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFC62828)),
                    ) { Text("Approve & check in") }
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Clear, contentDescription = "Dismiss") }
        }
    }
}

private fun LazyListScope.manualCheckIn(state: EventDetailState, canApprove: Boolean, vm: EventDetailViewModel) {
    item {
        var query by rememberSaveable { mutableStateOf("") }
        val checkedInIds = state.arrivals.map { it.checkIn.userId }.toSet()
        val q = query.trim()
        val matches = if (q.isEmpty()) emptyList() else state.people.filter {
            it.name.contains(q, true) || it.email.contains(q, true) || it.organization.contains(q, true)
        }.take(20)
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("No pass? Search by name or email") },
            )
            matches.forEach { person ->
                val application = state.applications[person.uid]
                val needsApproval = person.role == Role.PARTICIPANT && application != RegistrationStatus.APPROVED
                ListItem(
                    headlineContent = { Text(person.name) },
                    supportingContent = {
                        Text(
                            "${person.email} · ${person.role.label}" +
                                if (person.role == Role.PARTICIPANT) " · ${application?.label ?: "Didn't apply"}" else ""
                        )
                    },
                    trailingContent = {
                        when {
                            person.uid in checkedInIds -> Icon(Icons.Filled.Check, contentDescription = "Already checked in")
                            needsApproval && canApprove -> Button(onClick = {
                                vm.approveAndCheckIn(person, CheckInMethod.MANUAL)
                                query = ""
                            }) { Text("Approve & check in") }
                            needsApproval -> Text("Not approved", color = MaterialTheme.colorScheme.error)
                            else -> Button(onClick = {
                                vm.checkIn(person.uid, CheckInMethod.MANUAL)
                                query = ""
                            }) { Text("Check in") }
                        }
                    },
                )
            }
            if (q.isNotEmpty() && matches.isEmpty()) {
                Text("No one matches. They need to create an account first.", Modifier.padding(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.attendance(
    state: EventDetailState,
    me: UserProfile,
    vm: EventDetailViewModel,
    onOpenPerson: (String) -> Unit,
) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Attendance", style = MaterialTheme.typography.titleMedium)
            val summary = state.summary
            if (summary == null) {
                Text("${state.arrivals.size} checked in")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Checked in", "${summary.checkedIn}", Modifier.weight(1f))
                StatCard("Approved", "${summary.registered}", Modifier.weight(1f))
                StatCard("Not arrived", "${summary.notYetArrived}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("First-timers", "${summary.firstTimers}", Modifier.weight(1f))
                StatCard("Returning", "${summary.returning}", Modifier.weight(1f))
                StatCard("Staff in", "${summary.walkIns}", Modifier.weight(1f))
            }
            if (summary.byRole.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.byRole.toSortedMap().forEach { (role, count) ->
                        SuggestionChip(onClick = {}, label = { Text("${role.label}: $count") })
                    }
                }
            }
        }
    }
    items(state.arrivals, key = { it.checkIn.userId }) { arrival ->
        val time = remember(arrival.checkIn.checkedInAt) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(arrival.checkIn.checkedInAt))
        }
        ListItem(
            headlineContent = { Text(arrival.user?.name ?: "Unknown") },
            supportingContent = {
                Text(listOfNotNull(arrival.user?.role?.label, arrival.user?.organization?.takeIf { it.isNotBlank() })
                    .joinToString(" · "))
            },
            overlineContent = { Text("$time · ${if (arrival.checkIn.method == CheckInMethod.QR) "QR" else "Manual"}") },
            trailingContent = if (me.role.canCheckIn) {
                {
                    IconButton(onClick = { vm.undoCheckIn(arrival.checkIn.userId) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Undo check-in")
                    }
                }
            } else null,
            modifier = if (me.role.canSeeEveryone) Modifier.clickable { onOpenPerson(arrival.checkIn.userId) } else Modifier,
        )
        HorizontalDivider()
    }
}
