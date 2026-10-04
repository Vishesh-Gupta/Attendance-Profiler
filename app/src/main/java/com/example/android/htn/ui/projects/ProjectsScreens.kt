package com.example.android.htn.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Event
import com.example.android.htn.data.Project
import com.example.android.htn.data.PublishedResults
import com.example.android.htn.data.Role
import com.example.android.htn.data.Score
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.JudgeProgress
import com.example.android.htn.profiling.Judging
import com.example.android.htn.profiling.RankedProject
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.formatEventDates
import com.example.android.htn.ui.rememberApp
import com.example.android.htn.ui.userMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

private data class EventProjects(val event: Event, val count: Int, val progress: JudgeProgress?)

/**
 * The Projects tab. Participants see their teams' projects; judges see what they've been assigned;
 * organizers pick an event to manage judging and results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsTab(me: UserProfile, onOpenEventProjects: (String) -> Unit, onOpenTeam: (String) -> Unit) {
    val repository = rememberApp().repository
    val title = when (me.role) {
        Role.JUDGE -> "Judging"
        Role.ORGANIZER -> "Projects"
        else -> "My projects"
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        if (me.role.canJudge || me.role.canSeeEveryone) {
            val dataFlow = remember(me.uid, me.role) {
                combine(
                    repository.events(),
                    if (me.role.canJudge) repository.assignedProjects(me.uid) else repository.allProjects(),
                    if (me.role.canJudge) repository.scoresBy(me.uid) else flowOf(emptyList()),
                ) { events, projects, myScores ->
                    val byEvent = projects.groupBy { it.eventId }
                    events.mapNotNull { event ->
                        val eventProjects = byEvent[event.id] ?: return@mapNotNull null
                        EventProjects(
                            event,
                            eventProjects.size,
                            if (me.role.canJudge) Judging.progress(me.uid, eventProjects, myScores) else null,
                        )
                    }
                }
            }
            val rows by dataFlow.collectAsStateWithLifecycle(initialValue = null)
            val list = rows ?: return@Scaffold LoadingBox(Modifier.padding(padding))
            if (list.isEmpty()) {
                return@Scaffold EmptyMessage(
                    if (me.role.canJudge) "No projects have been assigned to you yet. Organizers assign judges once teams submit."
                    else "No projects have been submitted yet.",
                    Modifier.padding(padding),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.event.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.event.name) },
                        supportingContent = {
                            Column {
                                Text("${formatEventDates(row.event)} · ${row.count} project(s)" +
                                    if (me.role.canJudge) " assigned to you" else "")
                                row.progress?.let { ProgressLine(it) }
                            }
                        },
                        modifier = Modifier.clickable { onOpenEventProjects(row.event.id) },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            val dataFlow = remember(me.uid) {
                combine(repository.events(), repository.projectsOf(me.uid)) { events, projects ->
                    val eventsById = events.associateBy { it.id }
                    projects.mapNotNull { p -> eventsById[p.eventId]?.let { it to p } }
                        .sortedByDescending { it.first.startEpochDay }
                }
            }
            val rows by dataFlow.collectAsStateWithLifecycle(initialValue = null)
            val list = rows ?: return@Scaffold LoadingBox(Modifier.padding(padding))
            if (list.isEmpty()) {
                return@Scaffold EmptyMessage(
                    "You're not on any teams yet. Once you're checked in to an event, create or join a team from its page.",
                    Modifier.padding(padding),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.second.eventId }) { (event, project) ->
                    ListItem(
                        headlineContent = { Text(project.title) },
                        supportingContent = { Text("${event.name} · ${project.teamLabel}") },
                        modifier = Modifier.clickable { onOpenTeam(event.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ProgressLine(progress: JudgeProgress) {
    Column(Modifier.padding(top = 4.dp)) {
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.scored.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (progress.remaining == 0) "All ${progress.total} scored" else "${progress.scored} of ${progress.total} scored",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class EventProjectsState(
    val event: Event?,
    val projects: List<Project>,
    val leaderboard: List<RankedProject>,
    val myScores: Map<String, Score>,
    val judges: List<UserProfile>,
    val results: PublishedResults?,
)

/** One event's projects: a to-do list for judges; judging and results management for organizers. */
@Composable
fun EventProjectsScreen(
    eventId: String,
    me: UserProfile,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenResults: () -> Unit,
) {
    val repository = rememberApp().repository
    val organizer = me.role.canSeeEveryone
    val stateFlow = remember(eventId, me.uid, me.role) {
        combine(
            combine(repository.event(eventId), repository.results(eventId)) { e, r -> e to r },
            if (organizer) repository.projectsForEvent(eventId) else repository.assignedProjects(eventId, me.uid),
            if (organizer) repository.allScores() else repository.scoresBy(me.uid),
            if (organizer) repository.users() else flowOf(emptyList()),
        ) { (event, results), projects, scores, users ->
            val eventScores = scores.filter { it.eventId == eventId }
            EventProjectsState(
                event = event,
                projects = projects.sortedBy { it.title.lowercase() },
                leaderboard = if (organizer) Judging.leaderboard(projects, eventScores) else emptyList(),
                myScores = eventScores.filter { it.judgeId == me.uid }.associateBy { it.projectId },
                judges = users.filter { it.role == Role.JUDGE },
                results = results,
            )
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAssign by remember { mutableStateOf(false) }

    fun run(success: String, block: suspend () -> Unit) = scope.launch {
        try { block(); snackbar.showSnackbar(success) } catch (e: Exception) { snackbar.showSnackbar(e.userMessage()) }
    }

    Scaffold(
        topBar = { BackTopBar(state?.event?.name?.let { "$it projects" }.orEmpty(), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (organizer) {
                item {
                    OrganizerControls(
                        s,
                        onAssign = { showAssign = true },
                        onPublish = {
                            run(if (s.results == null) "Results published" else "Published results updated") {
                                repository.publishResults(eventId, Judging.resultEntries(s.leaderboard), me.uid)
                            }
                        },
                        onUnpublish = { run("Results hidden") { repository.unpublishResults(eventId) } },
                        onOpenResults = onOpenResults,
                    )
                }
                items(s.leaderboard, key = { it.project.id }) { ranked ->
                    val unassigned = ranked.project.assignedJudges.isEmpty()
                    ListItem(
                        leadingContent = {
                            Text(
                                if (ranked.averageTotal == null) "–" else "#${ranked.rank}",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.width(48.dp),
                            )
                        },
                        headlineContent = { Text(ranked.project.title) },
                        supportingContent = {
                            Text(
                                ranked.project.teamLabel + " · " +
                                    if (unassigned) "no judges assigned" else "${ranked.judgeCount}/${ranked.project.assignedJudges.size} judges scored",
                                color = if (unassigned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Text(
                                ranked.averageTotal?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "Unscored",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        modifier = Modifier.clickable { onOpenProject(ranked.project.id) },
                    )
                    HorizontalDivider()
                }
            } else {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Projects assigned to you", style = MaterialTheme.typography.titleMedium)
                        ProgressLine(Judging.progress(me.uid, s.projects, s.myScores.values.toList()))
                    }
                }
                items(s.projects, key = { it.id }) { project ->
                    val myScore = s.myScores[project.id]
                    ListItem(
                        headlineContent = { Text(project.title) },
                        supportingContent = { Text(project.teamLabel) },
                        trailingContent = {
                            if (myScore != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Check, contentDescription = "Scored", tint = MaterialTheme.colorScheme.primary)
                                    Text(" ${myScore.total}/${Score.MAX_TOTAL}")
                                }
                            } else {
                                Text("To score", color = MaterialTheme.colorScheme.tertiary)
                            }
                        },
                        modifier = Modifier.clickable { onOpenProject(project.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val s = state
    if (showAssign && s != null) {
        AutoAssignDialog(
            judgeCount = s.judges.size,
            onDismiss = { showAssign = false },
            onAssign = { perProject ->
                showAssign = false
                val changes = Judging.autoAssign(s.projects, s.judges.map { it.uid }, perProject)
                run(if (changes.isEmpty()) "Every project already has judges" else "Assigned judges to ${changes.size} project(s)") {
                    repository.setAssignedJudges(eventId, changes)
                }
            },
        )
    }
}

@Composable
private fun OrganizerControls(
    s: EventProjectsState,
    onAssign: () -> Unit,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
    onOpenResults: () -> Unit,
) {
    var confirmPublish by remember { mutableStateOf(false) }
    val unassigned = s.projects.count { it.assignedJudges.isEmpty() }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Judging", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                s.judges.isEmpty() -> "There are no judges yet. Give people the Judge role from the People tab."
                unassigned > 0 -> "$unassigned of ${s.projects.size} project(s) have no judges."
                else -> "Every project has judges. ${s.judges.size} judge(s) available."
            },
        )
        OutlinedButton(onClick = onAssign, enabled = s.judges.isNotEmpty() && s.projects.isNotEmpty()) {
            Text("Auto-assign judges")
        }
        Text("Results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (s.results == null) {
            Text("Not published. Participants can't see rankings or scores yet.")
            Button(onClick = { confirmPublish = true }, enabled = s.projects.isNotEmpty()) { Text("Publish results") }
        } else {
            Text("Published. Everyone can see the rankings, and teams can see their own scores and judges' notes.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenResults) { Text("View") }
                OutlinedButton(onClick = { confirmPublish = true }) { Text("Update") }
                TextButton(onClick = onUnpublish) { Text("Unpublish") }
            }
        }
        Text("Leaderboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("Ranked by average total across judges (out of ${Score.MAX_TOTAL}).", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmPublish) {
        val unscored = s.leaderboard.count { it.averageTotal == null }
        AlertDialog(
            onDismissRequest = { confirmPublish = false },
            title = { Text(if (s.results == null) "Publish results?" else "Update published results?") },
            text = {
                Text(
                    "Everyone will see the current leaderboard. Each team will also see its own scores and judges' notes, " +
                        "without judges' names." + if (unscored > 0) "\n\n$unscored project(s) haven't been scored yet." else ""
                )
            },
            confirmButton = { TextButton(onClick = { confirmPublish = false; onPublish() }) { Text("Publish") } },
            dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AutoAssignDialog(judgeCount: Int, onDismiss: () -> Unit, onAssign: (Int) -> Unit) {
    var perProject by remember { mutableIntStateOf(minOf(2, judgeCount)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-assign judges") },
        text = {
            Column {
                Text("Judges per project: $perProject")
                if (judgeCount > 1) {
                    Slider(
                        value = perProject.toFloat(),
                        onValueChange = { perProject = Math.round(it) },
                        valueRange = 1f..judgeCount.toFloat(),
                        steps = judgeCount - 2,
                    )
                }
                Text(
                    "Existing assignments are kept. New ones go to the judges with the fewest projects.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAssign(perProject) }) { Text("Assign") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
