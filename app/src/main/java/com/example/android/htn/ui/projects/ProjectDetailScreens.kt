package com.example.android.htn.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Project
import com.example.android.htn.data.Role
import com.example.android.htn.data.Score
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

private data class ProjectDetail(
    val project: Project?,
    val scores: List<Score>,
    val users: List<UserProfile>,
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
            ProjectDetail(project, scores.filter { it.eventId == eventId && it.projectId == projectId }, users)
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
            if (me.role.canSeeEveryone) {
                AssignJudges(
                    project = project,
                    judges = d.users.filter { it.role == Role.JUDGE },
                    onChange = { judges ->
                        scope.launch {
                            try { repository.setAssignedJudges(eventId, mapOf(projectId to judges)) }
                            catch (e: Exception) { snackbar.showSnackbar(e.userMessage()) }
                        }
                    },
                )
                AllScores(d.scores, d.users.associate { it.uid to it.name })
            }
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
                            data?.project?.let { repository.deleteProject(it) }
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
    Text("Team: ${project.teamLabel}", style = MaterialTheme.typography.bodyMedium)
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
    Text(
        "Other judges can't see your score. If results are published, the team sees your scores and notes without your name.",
        style = MaterialTheme.typography.bodySmall,
    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignJudges(project: Project, judges: List<UserProfile>, onChange: (List<String>) -> Unit) {
    Text("Judges", style = MaterialTheme.typography.titleMedium)
    if (judges.isEmpty()) {
        Text("There are no judges yet. Give people the Judge role from the People tab.")
        return
    }
    Text("Tap to assign or unassign. Only assigned judges can see and score this project.",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        judges.forEach { judge ->
            val assigned = judge.uid in project.assignedJudges
            FilterChip(
                selected = assigned,
                onClick = {
                    onChange(if (assigned) project.assignedJudges - judge.uid else project.assignedJudges + judge.uid)
                },
                label = { Text(judge.name) },
            )
        }
    }
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
