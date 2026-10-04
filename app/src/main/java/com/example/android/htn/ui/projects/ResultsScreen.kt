package com.example.android.htn.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Event
import com.example.android.htn.data.Project
import com.example.android.htn.data.PublishedResults
import com.example.android.htn.data.Score
import com.example.android.htn.data.UserProfile
import com.example.android.htn.ui.components.BackTopBar
import com.example.android.htn.ui.components.LoadingBox
import com.example.android.htn.ui.rememberApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class ResultsState(
    val event: Event?,
    val results: PublishedResults?,
    val myTeam: Project?,
    /** The signed-in person's team's scores; judges are anonymous. */
    val myScores: List<Score>,
)

/** Published results for an event, visible to everyone once an organizer publishes them. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ResultsScreen(eventId: String, me: UserProfile, onBack: () -> Unit) {
    val repository = rememberApp().repository
    val stateFlow = remember(eventId, me.uid) {
        val results = repository.results(eventId)
        val published = results.map { it != null }.distinctUntilChanged()
        // Scores are only readable once results are published, so (re)subscribe when that changes.
        val teamScores = combine(repository.teamOf(eventId, me.uid), published) { t, p -> t to p }
            .flatMapLatest { (t, p) ->
                if (t == null || !p) flowOf(t to emptyList()) else repository.scoresForProject(eventId, t.id).map { t to it }
            }
        combine(repository.event(eventId), results, teamScores) { event, r, (t, scores) ->
            ResultsState(event, r, t, scores)
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(topBar = { BackTopBar(state?.event?.name?.let { "$it results" }.orEmpty(), onBack) }) { padding ->
        val s = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val results = s.results ?: return@Scaffold EmptyMessage("Results haven't been published yet.", Modifier.padding(padding))
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Published ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(results.publishedAt))}. " +
                        "Scores are averages of judges' totals out of ${Score.MAX_TOTAL}.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            s.myTeam?.let { team -> item { MyTeamScores(team, s.myScores) } }
            items(results.entries, key = { it.projectId }) { entry ->
                val mine = entry.projectId == s.myTeam?.id
                ListItem(
                    leadingContent = {
                        Text(entry.rank?.let { "#$it" } ?: "–", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(48.dp))
                    },
                    headlineContent = { Text(if (mine) "${entry.title} (your team)" else entry.title) },
                    supportingContent = { Text(entry.members.joinToString(", ")) },
                    trailingContent = {
                        Text(entry.average?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "Unscored",
                            style = MaterialTheme.typography.titleMedium)
                    },
                    colors = if (mine) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else ListItemDefaults.colors(),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MyTeamScores(team: Project, scores: List<Score>) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your team's scores: ${team.title}", style = MaterialTheme.typography.titleMedium)
            if (scores.isEmpty()) {
                Text("No judge scored your project.")
                return@Column
            }
            Criterion.entries.forEach { c ->
                val avg = scores.map { it.values[c] ?: 0 }.average()
                Text("${c.label}: ${String.format(Locale.getDefault(), "%.1f", avg)} / ${Score.MAX}")
            }
            scores.forEachIndexed { index, score ->
                Text("Judge ${index + 1}: ${score.total} / ${Score.MAX_TOTAL}", style = MaterialTheme.typography.titleSmall)
                if (score.comment.isNotBlank()) Text("“${score.comment}”")
            }
        }
    }
}
