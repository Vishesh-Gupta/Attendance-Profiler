package com.example.android.htn.ui.projects

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Event
import com.example.android.htn.data.Project
import com.example.android.htn.data.TeamCode
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.components.QrCode
import com.example.android.htn.ui.components.rememberQrScanner
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** [attending]: registered or checked in, which is what it takes to create or join a team. */
private data class TeamState(val event: Event?, val team: Project?, val attending: Boolean)

/** A participant's team for one event: create or join it, share its code, edit the project, or leave. */
@Composable
fun TeamScreen(eventId: String, me: UserProfile, onBack: () -> Unit) {
    val repository = rememberApp().repository
    val stateFlow = remember(eventId, me.uid) {
        combine(
            repository.event(eventId),
            repository.teamOf(eventId, me.uid),
            repository.checkInsOf(me.uid),
            repository.registrationsOf(me.uid),
        ) { e, t, checkIns, registrations ->
            TeamState(e, t, checkIns.any { it.eventId == eventId } || registrations.any { it.eventId == eventId })
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun run(success: String? = null, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                success?.let { snackbar.showSnackbar(it) }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage())
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { BackTopBar(state?.team?.title ?: "Your team", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val event = s.event ?: return@Scaffold Text("This event no longer exists.", Modifier.padding(padding).padding(16.dp))
        val open = !event.isOver(todayEpochDay())
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            val team = s.team
            when {
                team != null -> {
                    if (open && team.members.size < Project.MAX_TEAM_SIZE) TeamCodeCard(event, team)
                    MembersCard(team, me)
                    ProjectForm(
                        key = team.id,
                        initial = team,
                        editable = open,
                        busy = busy,
                        submitLabel = "Save changes",
                        onSubmit = { title, description, link ->
                            run("Project saved") { repository.updateProject(eventId, team.id, title, description, link) }
                        },
                    )
                    if (open) {
                        HorizontalDivider()
                        Text("Switch teams", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "You can only be on one team per event. To join a different team, leave this one first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LeaveTeamButton(team, me, busy) { run("You left the team") { repository.leaveTeam(team, me) } }
                    }
                }
                !open -> Text("Project submissions for this event are closed.")
                !s.attending -> {
                    Text("Register for this event to create or join a team. You can do this before check-in.")
                    Button(onClick = { run("You're registered") { repository.register(eventId, me.uid) } }, enabled = !busy) {
                        Text("Register")
                    }
                }
                else -> {
                    JoinTeamSection(eventId, busy, onError = { scope.launch { snackbar.showSnackbar(it) } }) { code ->
                        run("You joined the team") { repository.joinTeam(eventId, code, me) }
                    }
                    HorizontalDivider()
                    Text("Or start a new team", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "You'll get a team code to share. Up to ${Project.MAX_TEAM_SIZE} people per team, " +
                            "and each teammate needs to be registered for the event.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ProjectForm(
                        key = "new",
                        initial = null,
                        editable = true,
                        busy = busy,
                        submitLabel = "Create team project",
                        onSubmit = { title, description, link ->
                            run("Team created. Share your team code with teammates.") {
                                repository.createProject(eventId, me, title, description, link)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinTeamSection(eventId: String, busy: Boolean, onError: (String) -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val scan = rememberQrScanner(
        onScanned = { raw ->
            val decoded = TeamCode.decodeQr(raw)
            when {
                decoded == null -> onError("That isn't a team code. Ask a teammate to open their team page.")
                decoded.first != eventId -> onError("That team code is for a different event.")
                else -> onJoin(decoded.second)
            }
        },
        onError = { onError(it.userMessage()) },
    )
    val normalized = TeamCode.normalize(code)

    Text("Join your team", style = MaterialTheme.typography.titleMedium)
    Text("Scan the QR code on a teammate's team page, or type their team code.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Scan team QR code") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.take(TeamCode.LENGTH + 4) },
            label = { Text("Team code") },
            placeholder = { Text("ABCDE-FGHJK") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            isError = code.isNotBlank() && normalized == null,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { normalized?.let(onJoin) }, enabled = normalized != null && !busy) { Text("Join") }
    }
}

@Composable
private fun TeamCodeCard(event: Event, team: Project) {
    val context = LocalContext.current
    val display = TeamCode.display(team.id)
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Team code", style = MaterialTheme.typography.titleSmall)
            Text(display, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            QrCode(
                content = TeamCode.qrPayload(event.id, team.id),
                contentDescription = "Team QR code $display",
                modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth().aspectRatio(1f),
            )
            Text("Teammates scan this or enter the code from their own account.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Join my team \"${team.title}\" for ${event.name} in Attendance Profiler. Team code: $display",
                    )
                }
                context.startActivity(Intent.createChooser(send, "Share team code"))
            }) { Text("Share code") }
        }
    }
}

@Composable
private fun MembersCard(team: Project, me: UserProfile) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Team (${team.members.size}/${Project.MAX_TEAM_SIZE})", style = MaterialTheme.typography.titleSmall)
            team.members.forEach { Text(if (it.uid == me.uid) "${it.name} (you)" else it.name) }
        }
    }
}

@Composable
private fun ProjectForm(
    key: String,
    initial: Project?,
    editable: Boolean,
    busy: Boolean,
    submitLabel: String,
    onSubmit: (title: String, description: String, link: String) -> Unit,
) {
    var title by rememberSaveable(key) { mutableStateOf(initial?.title.orEmpty()) }
    var description by rememberSaveable(key) { mutableStateOf(initial?.description.orEmpty()) }
    var link by rememberSaveable(key) { mutableStateOf(initial?.link.orEmpty()) }
    val valid = title.isNotBlank() && title.length <= 100 && description.isNotBlank() &&
        description.length <= 5000 && link.length <= 500
    val changed = initial == null || title != initial.title || description != initial.description || link != initial.link

    OutlinedTextField(title, { title = it }, label = { Text("Project name") }, singleLine = true,
        enabled = editable, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(description, { description = it }, label = { Text("What did you build?") }, minLines = 4,
        enabled = editable, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(link, { link = it }, label = { Text("Link (GitHub, Devpost, demo…)") }, singleLine = true,
        enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth())
    if (editable) {
        Button(
            onClick = { onSubmit(title, description, link) },
            enabled = valid && changed && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(submitLabel) }
    }
}

@Composable
private fun LeaveTeamButton(team: Project, me: UserProfile, busy: Boolean, onLeave: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val last = team.memberIds == listOf(me.uid)
    OutlinedButton(onClick = { confirm = true }, enabled = !busy) { Text(if (last) "Delete project & leave" else "Leave team") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (last) "Delete project?" else "Leave team?") },
            text = {
                Text(
                    if (last) "You're the only member, so the project will be deleted. You can then join or start another team."
                    else "Your teammates keep the project. You can then join or start another team."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onLeave() }) { Text(if (last) "Delete" else "Leave") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
