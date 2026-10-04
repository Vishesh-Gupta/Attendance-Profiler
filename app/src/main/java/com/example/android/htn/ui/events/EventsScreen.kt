package com.example.android.htn.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Event
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.formatEpochDay
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

private data class EventRow(val event: Event, val status: String?, val checkedInCount: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(me: UserProfile, onOpenEvent: (String) -> Unit) {
    val repository = rememberApp().repository
    val rowsFlow = remember(me.uid, me.role) {
        // Staff see everyone's check-ins (for counts); participants only their own.
        val checkIns = if (me.role.isStaff) repository.allCheckIns() else repository.checkInsOf(me.uid)
        combine(repository.events(), checkIns, repository.registrationsOf(me.uid)) { events, checkIns, regs ->
            val counts = checkIns.groupingBy { it.eventId }.eachCount()
            val mine = checkIns.filter { it.userId == me.uid }.map { it.eventId }.toSet()
            val registered = regs.map { it.eventId }.toSet()
            events.map { event ->
                val status = when (event.id) {
                    in mine -> "Checked in"
                    in registered -> "Registered"
                    else -> null
                }
                EventRow(event, status, if (me.role.isStaff) counts[event.id] ?: 0 else null)
            }
        }
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Events") }) },
        floatingActionButton = {
            if (me.role.canManageEvents) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New event")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = rows
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (me.role.canManageEvents) "No events yet. Tap + to create one, e.g. Hack the North."
                    else "No events yet. Check back once organizers add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.event.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.event.name) },
                        supportingContent = {
                            Text(listOf(formatEpochDay(row.event.startEpochDay), row.event.location)
                                .filter { it.isNotBlank() }.joinToString(" · "))
                        },
                        overlineContent = row.status?.let { { Text(it) } },
                        trailingContent = row.checkedInCount?.let { { Text("$it checked in") } },
                        modifier = Modifier.clickable { onOpenEvent(row.event.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        CreateEventDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, location, day ->
                showCreate = false
                scope.launch {
                    try {
                        onOpenEvent(repository.createEvent(name, location, day, me.uid))
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.userMessage())
                    }
                }
            },
        )
    }
}

@Composable
private fun CreateEventDialog(onDismiss: () -> Unit, onCreate: (String, String, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val parsedDate = remember(date) {
        try { LocalDate.parse(date.trim()) } catch (e: DateTimeParseException) { null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New event") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true)
                OutlinedTextField(
                    date, { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = parsedDate == null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name.length <= 100 && location.length <= 100 && parsedDate != null,
                onClick = { onCreate(name, location, parsedDate!!.toEpochDay()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
