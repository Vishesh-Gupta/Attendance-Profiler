package com.example.android.htn.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Event
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.calendar.EventCalendar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.formatEventDates
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private data class EventRow(val event: Event, val status: String?, val checkedInCount: Int?)

private enum class EventsView(val label: String) { LIST("List"), CALENDAR("Calendar") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(me: UserProfile, onOpenEvent: (String) -> Unit) {
    val repository = rememberApp().repository
    val rowsFlow = remember(me.uid, me.role) {
        // Check-in staff see everyone's check-ins (for counts); everyone else only their own.
        val checkIns = if (me.role.canCheckIn) repository.allCheckIns() else repository.checkInsOf(me.uid)
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
                EventRow(event, status, if (me.role.canCheckIn) counts[event.id] ?: 0 else null)
            }
        }
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var view by rememberSaveable { mutableStateOf(EventsView.LIST) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var createOn by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Events") }) },
        floatingActionButton = {
            if (me.role.canManageEvents) {
                FloatingActionButton(onClick = {
                    createOn = if (view == EventsView.CALENDAR) selectedDay else LocalDate.now()
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "New event")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                EventsView.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = view == option,
                        onClick = { view = option },
                        shape = SegmentedButtonDefaults.itemShape(index, EventsView.entries.size),
                    ) { Text(option.label) }
                }
            }
            val list = rows
            if (list == null) {
                LoadingBox()
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                when (view) {
                    EventsView.LIST -> listView(list, me, onOpenEvent)
                    EventsView.CALENDAR -> {
                        item {
                            EventCalendar(
                                month = month,
                                events = list.map { it.event },
                                selected = selectedDay,
                                onMonthChange = { month = it },
                                onSelect = { selectedDay = it; month = YearMonth.from(it) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        item {
                            Text(
                                selectedDay.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        val onDay = list.filter { it.event.isOn(selectedDay.toEpochDay()) }
                        if (onDay.isEmpty()) {
                            item { Text("Nothing scheduled.", Modifier.padding(horizontal = 16.dp)) }
                        }
                        eventRows(onDay, onOpenEvent)
                    }
                }
            }
        }
    }

    createOn?.let { day ->
        CreateEventDialog(
            initialDate = day,
            onDismiss = { createOn = null },
            onCreate = { name, location, description, start, end ->
                createOn = null
                scope.launch {
                    try {
                        onOpenEvent(repository.createEvent(name, location, description, start, end, me.uid))
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.userMessage())
                    }
                }
            },
        )
    }
}

private fun LazyListScope.listView(rows: List<EventRow>, me: UserProfile, onOpenEvent: (String) -> Unit) {
    if (rows.isEmpty()) {
        item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (me.role.canManageEvents) "No events yet. Tap + to create one, e.g. Hack the North."
                    else "No events yet. Check back once organizers add one.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }
    val today = todayEpochDay()
    val (past, current) = rows.partition { it.event.isOver(today) }
    if (current.isNotEmpty()) {
        item { SectionHeader("Happening now & upcoming") }
        eventRows(current.sortedBy { it.event.startEpochDay }, onOpenEvent)
    }
    if (past.isNotEmpty()) {
        item { SectionHeader("Past") }
        eventRows(past, onOpenEvent)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

private fun LazyListScope.eventRows(rows: List<EventRow>, onOpenEvent: (String) -> Unit) {
    items(rows, key = { it.event.id }) { row ->
        ListItem(
            headlineContent = { Text(row.event.name) },
            supportingContent = {
                Text(listOf(formatEventDates(row.event), row.event.location).filter { it.isNotBlank() }.joinToString(" · "))
            },
            overlineContent = row.status?.let { { Text(it) } },
            trailingContent = row.checkedInCount?.let { { Text("$it checked in") } },
            modifier = Modifier.clickable { onOpenEvent(row.event.id) },
        )
        HorizontalDivider()
    }
}

private fun parseDate(text: String): LocalDate? =
    try { LocalDate.parse(text.trim()) } catch (e: DateTimeParseException) { null }

@Composable
private fun CreateEventDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onCreate: (name: String, location: String, description: String, start: Long, end: Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(initialDate.toString()) }
    var end by remember { mutableStateOf(initialDate.toString()) }
    val startDate = parseDate(start)
    val endDate = parseDate(end)
    val rangeValid = startDate != null && endDate != null && !endDate.isBefore(startDate) &&
        endDate.toEpochDay() - startDate.toEpochDay() <= 31

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New event") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("Starts (YYYY-MM-DD)") }, singleLine = true,
                    isError = startDate == null)
                OutlinedTextField(end, { end = it }, label = { Text("Ends (YYYY-MM-DD)") }, singleLine = true,
                    isError = startDate != null && !rangeValid,
                    supportingText = { Text("Same as start for a one-day event; at most 31 days") })
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") },
                    minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name.length <= 100 && location.length <= 100 &&
                    description.length <= 2000 && rangeValid,
                onClick = { onCreate(name, location, description, startDate!!.toEpochDay(), endDate!!.toEpochDay()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
