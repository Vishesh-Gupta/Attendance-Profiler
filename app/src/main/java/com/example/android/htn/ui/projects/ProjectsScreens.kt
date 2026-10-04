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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Event
import com.example.android.htn.data.Project
import com.example.android.htn.data.Score
import com.example.android.htn.data.UserProfile
import com.example.android.htn.profiling.JudgeProgress
import com.example.android.htn.profiling.Judging
import com.example.android.htn.profiling.RankedProject
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.formatEventDates
import com.example.android.htn.ui.rememberApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

private data class EventProjects(val event: Event, val count: Int, val progress: JudgeProgress?)

/**
 * The Projects tab. Participants see their own submissions; judges see what's left to score;
 * organizers pick an event to see its leaderboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsTab(me: UserProfile, onOpenEventProjects: (String) -> Unit, onOpenOwnProject: (String) -> Unit) {
    val repository = rememberApp().repository
    val title = when {
        me.role.canJudge -> "Judging"
        me.role.canSeeAllProjects -> "Projects"
        else -> "My projects"
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        if (me.role.canSeeAllProjects) {
            val dataFlow = remember(me.uid, me.role) {
                combine(
                    repository.events(),
                    repository.allProjects(),
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
            if (list.isEmpty()) return@Scaffold EmptyMessage("No projects have been submitted yet.", Modifier.padding(padding))
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.event.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.event.name) },
                        supportingContent = {
                            Column {
                                Text("${formatEventDates(row.event)} · ${row.count} project(s)")
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
                    "You haven't submitted any projects. Once you're checked in to an event, submit your project from its page.",
                    Modifier.padding(padding),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.second.eventId }) { (event, project) ->
                    ListItem(
                        headlineContent = { Text(project.title) },
                        supportingContent = { Text("${event.name} · ${formatEventDates(event)}") },
                        modifier = Modifier.clickable { onOpenOwnProject(event.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: JudgeProgress) {
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
private fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class EventProjectsState(
    val event: Event?,
    val projects: List<Project>,
    val leaderboard: List<RankedProject>,
    val myScores: Map<String, Score>,
)

/** One event's projects: a to-do list for judges, a leaderboard for organizers. */
@Composable
fun EventProjectsScreen(eventId: String, me: UserProfile, onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val repository = rememberApp().repository
    val stateFlow = remember(eventId, me.uid, me.role) {
        combine(
            repository.event(eventId),
            repository.projectsForEvent(eventId),
            if (me.role.canSeeEveryone) repository.allScores() else repository.scoresBy(me.uid),
        ) { event, projects, scores ->
            val eventScores = scores.filter { it.eventId == eventId }
            EventProjectsState(
                event = event,
                projects = projects.sortedBy { it.title.lowercase() },
                leaderboard = if (me.role.canSeeEveryone) Judging.leaderboard(projects, eventScores) else emptyList(),
                myScores = eventScores.filter { it.judgeId == me.uid }.associateBy { it.projectId },
            )
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(topBar = { BackTopBar(state?.event?.name?.let { "$it projects" }.orEmpty(), onBack) }) { padding ->
        val s = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (me.role.canSeeEveryone) {
                item {
                    Text(
                        "Ranked by average total score across judges (out of ${Score.MAX_TOTAL}).",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(s.leaderboard, key = { it.project.submittedBy }) { ranked ->
                    ListItem(
                        leadingContent = {
                            Text(
                                if (ranked.averageTotal == null) "–" else "#${ranked.rank}",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.width(48.dp),
                            )
                        },
                        headlineContent = { Text(ranked.project.title) },
                        supportingContent = { Text(teamLabel(ranked.project)) },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    ranked.averageTotal?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "Unscored",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("${ranked.judgeCount} judge(s)", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.clickable { onOpenProject(ranked.project.submittedBy) },
                    )
                    HorizontalDivider()
                }
            } else {
                item {
                    Column(Modifier.padding(16.dp)) {
                        ProgressLine(Judging.progress(me.uid, s.projects, s.myScores.values.toList()))
                    }
                }
                items(s.projects, key = { it.submittedBy }) { project ->
                    val myScore = s.myScores[project.submittedBy]
                    ListItem(
                        headlineContent = { Text(project.title) },
                        supportingContent = { Text(teamLabel(project)) },
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
                        modifier = Modifier.clickable { onOpenProject(project.submittedBy) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

fun teamLabel(project: Project): String =
    (listOf(project.submitterName) + project.teamMembers).distinct().joinToString(", ")
