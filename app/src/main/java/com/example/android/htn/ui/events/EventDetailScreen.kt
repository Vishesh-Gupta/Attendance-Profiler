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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.android.htn.data.AttendanceRepository
import com.example.android.htn.data.AttendeeRole
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.StatCard
import com.example.android.htn.ui.formatEpochDay
import com.example.android.htn.ui.rememberRepository
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetailScreen(eventId: Long, onBack: () -> Unit, onOpenAttendee: (Long) -> Unit) {
    val repository = rememberRepository()
    val vm: EventDetailViewModel = viewModel(
        key = "event-$eventId",
        factory = viewModelFactory { initializer { EventDetailViewModel(eventId, repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRegister by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val checkedInIds = state.checkedIn.map { it.attendee.id }.toSet()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Scaffold(
        topBar = {
            BackTopBar(state.event?.name ?: "", onBack) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete event")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.event?.let { event ->
                        Text(
                            listOf(formatEpochDay(event.startEpochDay), event.location)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.summary?.let { summary ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Checked in", "${summary.checkedIn}", Modifier.weight(1f))
                            StatCard("First-timers", "${summary.firstTimers}", Modifier.weight(1f))
                            StatCard("Returning", "${summary.returning}", Modifier.weight(1f))
                        }
                        if (summary.byRole.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                summary.byRole.toSortedMap().forEach { (role, count) ->
                                    SuggestionChip(onClick = {}, label = { Text("${role.label}: $count") })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query.value = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        label = { Text("Find attendee to check in") },
                    )
                    FilledTonalButton(onClick = { showRegister = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Register new attendee")
                    }
                }
            }

            items(results, key = { "result-${it.id}" }) { attendee ->
                val alreadyIn = attendee.id in checkedInIds
                ListItem(
                    headlineContent = { Text(attendee.name) },
                    supportingContent = { Text("${attendee.email} · ${attendee.role.label}") },
                    trailingContent = {
                        if (alreadyIn) {
                            Icon(Icons.Filled.Check, contentDescription = "Already checked in")
                        } else {
                            Button(onClick = { vm.checkIn(attendee) }) { Text("Check in") }
                        }
                    },
                )
            }
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    item { Text("No match. Register them as a new attendee.", Modifier.padding(16.dp)) }
                }
                item { HorizontalDivider(thickness = 4.dp) }
            }

            item {
                Text(
                    "Checked in (${state.checkedIn.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(state.checkedIn, key = { "in-${it.attendee.id}" }) { item ->
                ListItem(
                    headlineContent = { Text(item.attendee.name) },
                    supportingContent = {
                        Text(listOf(item.attendee.role.label, item.attendee.organization)
                            .filter { it.isNotBlank() }.joinToString(" · "))
                    },
                    overlineContent = { Text(timeFormat.format(Date(item.checkedInAt))) },
                    trailingContent = {
                        IconButton(onClick = { vm.undoCheckIn(item.attendee.id) }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Undo check-in")
                        }
                    },
                    modifier = Modifier.clickable { onOpenAttendee(item.attendee.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (showRegister) {
        RegisterAttendeeDialog(
            initialQuery = query,
            onDismiss = { showRegister = false },
            onRegister = { name, email, org, role ->
                showRegister = false
                vm.registerAndCheckIn(name, email, org, role)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text("All check-ins for this event will be removed. Attendee profiles are kept.") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterAttendeeDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onRegister: (String, String, String, AttendeeRole) -> Unit,
) {
    val queryIsEmail = '@' in initialQuery
    var name by remember { mutableStateOf(if (queryIsEmail) "" else initialQuery.trim()) }
    var email by remember { mutableStateOf(if (queryIsEmail) initialQuery.trim() else "") }
    var organization by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(AttendeeRole.HACKER) }
    var roleMenuOpen by remember { mutableStateOf(false) }
    val emailValid = AttendanceRepository.isValidEmail(email)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register & check in") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    isError = email.isNotEmpty() && !emailValid,
                    supportingText = { Text("Used to recognise them at future events") },
                )
                OutlinedTextField(
                    organization, { organization = it },
                    label = { Text("School / company") },
                    singleLine = true,
                )
                ExposedDropdownMenuBox(expanded = roleMenuOpen, onExpandedChange = { roleMenuOpen = it }) {
                    OutlinedTextField(
                        value = role.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleMenuOpen) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        AttendeeRole.entries.forEach {
                            DropdownMenuItem(text = { Text(it.label) }, onClick = {
                                role = it
                                roleMenuOpen = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && emailValid,
                onClick = { onRegister(name, email, organization, role) },
            ) { Text("Check in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
