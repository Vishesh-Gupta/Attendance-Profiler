package com.example.android.htn.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Event
import com.example.android.htn.data.Project
import com.example.android.htn.data.Score
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.todayEpochDay
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

private data class ProjectDetail(
    val project: Project?,
    val scores: List<Score>,
    val judgeNames: Map<String, String>,
)

/** Judges score the project here; organizers see every judge's scores and can remove the project. */
@Composable
fun ProjectDetailScreen(eventId: String, projectId: String, me: UserProfile, onBack: () -> Unit) {
    val repository = rememberApp().repository
    val dataFlow = remember(eventId, projectId, me.uid, me.role) {
        combine(
            repository.project(eventId, projectId),
            if (me.role.canSeeEveryone) repository.allScores() else repository.scoresBy(me.uid),
            if (me.role.canSeeEveryone) repository.users() else flowOf(emptyList()),
        ) { project, scores, users ->
            ProjectDetail(
                project,
                scores.filter { it.eventId == eventId && it.projectId == projectId },
                users.associate { it.uid to it.name },
            )
        }
    }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(data?.project?.title.orEmpty(), onBack) {
                if (me.role.canSeeEveryone && data?.project != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove project")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = data ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val project = d.project ?: return@Scaffold Text("This project was removed.", Modifier.padding(padding).padding(16.dp))
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectInfo(project)
            HorizontalDivider()
            if (me.role.canJudge) {
                ScoreForm(
                    existing = d.scores.firstOrNull { it.judgeId == me.uid },
                    onSave = { values, comment ->
                        scope.launch {
                            try {
                                repository.saveScore(eventId, projectId, me.uid, values, comment)
                                snackbar.showSnackbar("Score saved")
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.userMessage())
                            }
                        }
                    },
                )
            }
            if (me.role.canSeeEveryone) AllScores(d.scores, d.judgeNames)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove project?") },
            text = { Text("The submission and all of its scores will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            repository.deleteProject(eventId, projectId, asOrganizer = true)
                            onBack()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.userMessage())
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectInfo(project: Project) {
    val uriHandler = LocalUriHandler.current
    Text(project.title, style = MaterialTheme.typography.headlineSmall)
    Text("Team: ${teamLabel(project)}", style = MaterialTheme.typography.bodyMedium)
    Text(project.description)
    if (project.link.isNotBlank()) {
        TextButton(onClick = { runCatching { uriHandler.openUri(project.link) } }) { Text(project.link) }
    }
}

@Composable
private fun ScoreForm(existing: Score?, onSave: (Map<Criterion, Int>, String) -> Unit) {
    // Re-seed the form when the saved score first arrives from the server.
    val values = remember(existing) {
        mutableStateMapOf<Criterion, Int>().apply {
            Criterion.entries.forEach { put(it, existing?.values?.get(it) ?: 5) }
        }
    }
    var comment by rememberSaveable(existing) { mutableStateOf(existing?.comment.orEmpty()) }

    Text(if (existing == null) "Your score" else "Your score (saved)", style = MaterialTheme.typography.titleMedium)
    Text("Other judges can't see your score.", style = MaterialTheme.typography.bodySmall)
    Criterion.entries.forEach { criterion ->
        val value = values.getValue(criterion)
        Column {
            Row {
                Text(criterion.label, Modifier.weight(1f))
                Text("$value / ${Score.MAX}", style = MaterialTheme.typography.titleSmall)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { values[criterion] = Math.round(it) },
                valueRange = Score.MIN.toFloat()..Score.MAX.toFloat(),
                steps = Score.MAX - Score.MIN - 1,
            )
        }
    }
    Text("Total: ${values.values.sum()} / ${Score.MAX_TOTAL}", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        comment, { comment = it },
        label = { Text("Notes (optional)") },
        minLines = 2,
        isError = comment.length > 2000,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSave(values.toMap(), comment) },
        enabled = comment.length <= 2000,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (existing == null) "Submit score" else "Update score") }
}

@Composable
private fun AllScores(scores: List<Score>, judgeNames: Map<String, String>) {
    Text("Judges' scores", style = MaterialTheme.typography.titleMedium)
    if (scores.isEmpty()) {
        Text("No judge has scored this project yet.")
        return
    }
    val average = scores.map { it.total }.average()
    Text("Average: ${String.format(Locale.getDefault(), "%.1f", average)} / ${Score.MAX_TOTAL}")
    scores.sortedByDescending { it.total }.forEach { score ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(judgeNames[score.judgeId] ?: "Judge", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("${score.total} / ${Score.MAX_TOTAL}", style = MaterialTheme.typography.titleSmall)
                }
                Text(Criterion.entries.joinToString(" · ") { "${it.label} ${score.values[it]}" },
                    style = MaterialTheme.typography.bodySmall)
                if (score.comment.isNotBlank()) Text(score.comment)
            }
        }
    }
}

private data class OwnProject(val event: Event?, val project: Project?)

/** A participant's own submission for one event: create, edit, or (after the event) view. */
@Composable
fun ProjectEditScreen(eventId: String, me: UserProfile, onBack: () -> Unit) {
    val repository = rememberApp().repository
    val dataFlow = remember(eventId, me.uid) {
        combine(repository.event(eventId), repository.project(eventId, me.uid)) { e, p -> OwnProject(e, p) }
    }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar(if (data?.project == null) "Submit project" else "Your project", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = data ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val event = d.event ?: return@Scaffold Text("This event no longer exists.", Modifier.padding(padding).padding(16.dp))
        val editable = !event.isOver(todayEpochDay())

        // Seed the form once, when the saved project (if any) has loaded.
        var title by rememberSaveable { mutableStateOf<String?>(null) }
        var description by rememberSaveable { mutableStateOf("") }
        var link by rememberSaveable { mutableStateOf("") }
        var team by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(Unit) {
            if (title == null) {
                title = d.project?.title.orEmpty()
                description = d.project?.description.orEmpty()
                link = d.project?.link.orEmpty()
                team = d.project?.teamMembers?.joinToString(", ").orEmpty()
            }
        }
        val teamMembers = team.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val valid = !title.isNullOrBlank() && title!!.length <= 100 && description.isNotBlank() &&
            description.length <= 5000 && link.length <= 500 && teamMembers.size <= 10 && teamMembers.all { it.length <= 100 }

        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            if (!editable) Text("Submissions for this event are closed.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(title.orEmpty(), { title = it }, label = { Text("Project name") }, singleLine = true,
                enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("What did you build?") }, minLines = 4,
                enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(link, { link = it }, label = { Text("Link (GitHub, Devpost, demo…)") }, singleLine = true,
                enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(team, { team = it }, label = { Text("Teammates (comma-separated, optional)") },
                enabled = editable, supportingText = { Text("You're included automatically. Up to 10 names.") },
                modifier = Modifier.fillMaxWidth())
            if (editable) {
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                repository.saveProject(eventId, me, title.orEmpty(), description, link, teamMembers)
                                snackbar.showSnackbar("Project saved")
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.userMessage())
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (d.project == null) "Submit" else "Save changes") }
                if (d.project != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                try {
                                    repository.deleteProject(eventId, me.uid, asOrganizer = false)
                                    onBack()
                                } catch (e: Exception) {
                                    snackbar.showSnackbar(e.userMessage())
                                }
                            }
                        },
                    ) { Text("Withdraw submission") }
                }
            }
        }
    }
}
