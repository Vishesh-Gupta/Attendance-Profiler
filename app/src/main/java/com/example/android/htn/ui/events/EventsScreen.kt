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
import com.example.android.htn.ui.formatEpochDay
import com.example.android.htn.ui.rememberRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(onOpenEvent: (Long) -> Unit) {
    val repository = rememberRepository()
    val events by repository.eventsWithCounts.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Events") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New event")
            }
        },
    ) { padding ->
        val list = events
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No events yet.\nTap + to create one, e.g. Hack the North.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.event.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.event.name) },
                        supportingContent = {
                            Text(listOf(formatEpochDay(item.event.startEpochDay), item.event.location)
                                .filter { it.isNotBlank() }.joinToString(" · "))
                        },
                        trailingContent = { Text("${item.attendeeCount} checked in") },
                        modifier = Modifier.clickable { onOpenEvent(item.event.id) },
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
                scope.launch { onOpenEvent(repository.createEvent(name, location, day)) }
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
                enabled = name.isNotBlank() && parsedDate != null,
                onClick = { onCreate(name, location, parsedDate!!.toEpochDay()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
