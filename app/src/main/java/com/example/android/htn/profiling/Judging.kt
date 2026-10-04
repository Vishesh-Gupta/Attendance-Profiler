package com.example.android.htn.profiling

import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Project
import com.example.android.htn.data.Score

data class RankedProject(
    val rank: Int,
    val project: Project,
    /** Mean of judges' totals, or null if nobody has scored it yet. */
    val averageTotal: Double?,
    val averageByCriterion: Map<Criterion, Double>,
    val judgeCount: Int,
)

data class JudgeProgress(val scored: Int, val total: Int) {
    val remaining: Int get() = total - scored
}

object Judging {

    /**
     * Ranks one event's projects by average total score. Unscored projects go last. Projects with
     * the same average share a rank (1, 2, 2, 4).
     */
    fun leaderboard(projects: List<Project>, scores: List<Score>): List<RankedProject> {
        val byProject = scores.groupBy { it.eventId to it.projectId }
        val unranked = projects.map { project ->
            val projectScores = byProject[project.eventId to project.submittedBy].orEmpty()
            RankedProject(
                rank = 0,
                project = project,
                averageTotal = projectScores.takeIf { it.isNotEmpty() }?.map { it.total }?.average(),
                averageByCriterion = if (projectScores.isEmpty()) emptyMap() else Criterion.entries.associateWith { c ->
                    projectScores.map { it.values[c] ?: 0 }.average()
                },
                judgeCount = projectScores.size,
            )
        }.sortedWith(
            compareByDescending<RankedProject> { it.averageTotal ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.project.title.lowercase() }
        )

        var rank = 0
        return unranked.mapIndexed { index, item ->
            val tiedWithPrevious = index > 0 && item.averageTotal != null &&
                item.averageTotal == unranked[index - 1].averageTotal
            if (!tiedWithPrevious) rank = index + 1
            item.copy(rank = rank)
        }
    }

    fun progress(judgeId: String, projects: List<Project>, scores: List<Score>): JudgeProgress {
        val projectKeys = projects.map { it.eventId to it.submittedBy }.toSet()
        val scored = scores.count { it.judgeId == judgeId && (it.eventId to it.projectId) in projectKeys }
        return JudgeProgress(scored = scored, total = projects.size)
    }
}
