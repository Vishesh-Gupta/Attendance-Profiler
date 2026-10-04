package com.example.android.htn.profiling

import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Project
import com.example.android.htn.data.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JudgingTest {

    private fun project(owner: String, title: String, eventId: String = "htn") =
        Project(eventId, owner, owner, title, "desc", "", emptyList(), 0)

    private fun score(projectId: String, judge: String, vararg values: Int, eventId: String = "htn") =
        Score(eventId, projectId, judge, Criterion.entries.zip(values.toList()).toMap(), "")

    @Test
    fun ranksByAverageTotalWithUnscoredLast() {
        val projects = listOf(project("a", "Alpha"), project("b", "Beta"), project("c", "Gamma"))
        val scores = listOf(
            score("a", "j1", 5, 5, 5, 5), // 20
            score("a", "j2", 7, 7, 7, 7), // 28 -> avg 24
            score("b", "j1", 9, 9, 9, 9), // 36
        )
        val board = Judging.leaderboard(projects, scores)

        assertEquals(listOf("Beta", "Alpha", "Gamma"), board.map { it.project.title })
        assertEquals(listOf(1, 2, 3), board.map { it.rank })
        assertEquals(24.0, board[1].averageTotal!!, 1e-9)
        assertEquals(6.0, board[1].averageByCriterion.getValue(Criterion.DESIGN), 1e-9)
        assertEquals(2, board[1].judgeCount)
        assertNull(board[2].averageTotal)
    }

    @Test
    fun tiesShareARank() {
        val projects = listOf(project("a", "A"), project("b", "B"), project("c", "C"))
        val scores = listOf(score("a", "j", 8, 8, 8, 8), score("b", "j", 8, 8, 8, 8), score("c", "j", 1, 1, 1, 1))
        assertEquals(listOf(1, 1, 3), Judging.leaderboard(projects, scores).map { it.rank })
    }

    @Test
    fun scoresFromOtherEventsAreIgnored() {
        val board = Judging.leaderboard(listOf(project("a", "A")), listOf(score("a", "j", 9, 9, 9, 9, eventId = "other")))
        assertNull(board.single().averageTotal)
    }

    @Test
    fun judgeProgress() {
        val projects = listOf(project("a", "A"), project("b", "B"))
        val scores = listOf(score("a", "j1", 5, 5, 5, 5), score("b", "j2", 5, 5, 5, 5))
        assertEquals(JudgeProgress(scored = 1, total = 2), Judging.progress("j1", projects, scores))
    }
}
