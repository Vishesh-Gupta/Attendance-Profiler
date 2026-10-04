package com.example.android.htn.ui.events

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.android.htn.data.Event
import com.example.android.htn.data.Registration
import com.example.android.htn.data.RegistrationStatus
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.AttendanceProfile
import com.example.android.htn.profiling.AttendanceProfiler
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.components.TierBadge
import com.example.android.htn.ui.percent
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class Applicant(val user: UserProfile?, val registration: Registration, val history: AttendanceProfile)

private data class ApplicationsState(val event: Event?, val applicants: List<Applicant>)

/**
 * Organizers review one event's applications. Each decision emails the applicant (via the
 * onRegistrationDecision Cloud Function). Attendance history helps spot frequent no-shows.
 */
@Composable
fun ApplicationsScreen(eventId: String, me: UserProfile, onBack: () -> Unit, onOpenPerson: (String) -> Unit) {
    val repository = rememberApp().repository
    val stateFlow = remember(eventId) {
        combine(
            repository.event(eventId),
            repository.events(),
            repository.users(),
            repository.allCheckIns(),
            repository.allRegistrations(),
        ) { event, events, users, checkIns, registrations ->
            val usersById = users.associateBy { it.uid }
            // History from other events only, so this application doesn't count against them.
            val otherEvents = events.filter { it.id != eventId }
            val today = todayEpochDay()
            ApplicationsState(
                event,
                registrations.filter { it.eventId == eventId }.sortedBy { it.registeredAt }.map { reg ->
                    Applicant(
                        usersById[reg.userId],
                        reg,
                        AttendanceProfiler.profile(reg.userId, otherEvents, checkIns, registrations, today),
                    )
                },
            )
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf(RegistrationStatus.PENDING) }
    var pendingDecision by remember { mutableStateOf<Pair<Applicant, RegistrationStatus>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { BackTopBar(state?.event?.name?.let { "$it applications" }.orEmpty(), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val counts = s.applicants.groupingBy { it.registration.status }.eachCount()
        val visible = s.applicants.filter { it.registration.status == filter }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RegistrationStatus.entries.forEach {
                        FilterChip(
                            selected = filter == it,
                            onClick = { filter = it },
                            label = { Text("${it.label} (${counts[it] ?: 0})") },
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item { Text("No ${filter.label.lowercase()} applications.", Modifier.padding(16.dp)) }
            }
            items(visible, key = { it.registration.userId }) { applicant ->
                ApplicantCard(
                    applicant,
                    onDecide = { status -> pendingDecision = applicant to status },
                    onOpenPerson = { onOpenPerson(applicant.registration.userId) },
                )
            }
        }
    }

    pendingDecision?.let { (applicant, status) ->
        val name = applicant.user?.name ?: "this person"
        AlertDialog(
            onDismissRequest = { pendingDecision = null },
            title = { Text("${decisionVerb(status)} $name?") },
            text = {
                Text(
                    if (status == RegistrationStatus.PENDING) "Their application goes back to pending. No email is sent."
                    else "$name will be emailed at ${applicant.user?.email ?: "their account email"}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDecision = null
                    scope.launch {
                        try {
                            repository.setRegistrationStatus(eventId, applicant.registration.userId, status, me.uid)
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.userMessage())
                        }
                    }
                }) { Text(decisionVerb(status)) }
            },
            dismissButton = { TextButton(onClick = { pendingDecision = null }) { Text("Cancel") } },
        )
    }
}

private fun decisionVerb(status: RegistrationStatus) = when (status) {
    RegistrationStatus.APPROVED -> "Approve"
    RegistrationStatus.WAITLISTED -> "Waitlist"
    RegistrationStatus.DECLINED -> "Decline"
    RegistrationStatus.PENDING -> "Move back to pending"
}

@Composable
private fun ApplicantCard(applicant: Applicant, onDecide: (RegistrationStatus) -> Unit, onOpenPerson: () -> Unit) {
    val user = applicant.user
    val reg = applicant.registration
    val history = applicant.history
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(user?.name ?: "Unknown account", style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(user?.email, user?.organization?.takeIf { it.isNotBlank() }).joinToString(" · "))
            Text(
                "Applied ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reg.registeredAt))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierBadge(history.tier)
            }
            Text(
                if (history.eligibleEvents == 0) "No previous events."
                else "Previously: ${history.eventsAttended} attended · ${percent(history.attendanceRate)} attendance · " +
                    "${history.noShows} no-show(s)",
                color = if (history.noShows > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(emailStatus(reg), style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RegistrationStatus.entries.filter { it != reg.status && it != RegistrationStatus.PENDING }.forEach {
                    OutlinedButton(onClick = { onDecide(it) }) { Text(decisionVerb(it)) }
                }
                if (reg.status != RegistrationStatus.PENDING) {
                    TextButton(onClick = { onDecide(RegistrationStatus.PENDING) }) { Text("Undo") }
                }
                TextButton(onClick = onOpenPerson) { Text("Profile") }
            }
        }
    }
}

private fun emailStatus(reg: Registration): String = when {
    reg.status == RegistrationStatus.PENDING -> "Not reviewed yet."
    reg.notifiedStatus == reg.status -> "Emailed: ${reg.status.label.lowercase()}."
    else -> "Sending ${reg.status.label.lowercase()} email…"
}
