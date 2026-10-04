package com.example.android.htn.profiling

import com.example.android.htn.data.Criterion
import com.example.android.htn.data.Project
import com.example.android.htn.data.Score
import com.example.android.htn.data.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgingTest {

    private fun project(id: String, title: String = id, eventId: String = "htn", judges: List<String> = emptyList()) =
        Project(id, eventId, title, "desc", "", listOf(TeamMember("u-$id", "Ada"), TeamMember("v-$id", "Bob")),
            "u-$id", judges, 0)

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
        val projects = listOf(project("a"), project("b"), project("c"))
        val scores = listOf(score("a", "j", 8, 8, 8, 8), score("b", "j", 8, 8, 8, 8), score("c", "j", 1, 1, 1, 1))
        assertEquals(listOf(1, 1, 3), Judging.leaderboard(projects, scores).map { it.rank })
    }

    @Test
    fun scoresFromOtherEventsAreIgnored() {
        val board = Judging.leaderboard(listOf(project("a")), listOf(score("a", "j", 9, 9, 9, 9, eventId = "other")))
        assertNull(board.single().averageTotal)
    }

    @Test
    fun publishedEntriesCarryTeamNamesAndLeaveUnscoredUnranked() {
        val board = Judging.leaderboard(listOf(project("a", "Alpha"), project("b", "Beta")), listOf(score("a", "j", 9, 9, 9, 9)))
        val entries = Judging.resultEntries(board)
        assertEquals(listOf("Ada", "Bob"), entries[0].members)
        assertEquals(1, entries[0].rank)
        assertEquals(36.0, entries[0].average!!, 1e-9)
        assertNull(entries[1].rank)
        assertNull(entries[1].average)
    }

    @Test
    fun judgeProgress() {
        val projects = listOf(project("a"), project("b"))
        val scores = listOf(score("a", "j1", 5, 5, 5, 5), score("b", "j2", 5, 5, 5, 5))
        assertEquals(JudgeProgress(scored = 1, total = 2), Judging.progress("j1", projects, scores))
    }

    @Test
    fun autoAssignBalancesLoad() {
        val projects = (1..10).map { project("p%02d".format(it)) }
        val changes = Judging.autoAssign(projects, listOf("j1", "j2", "j3"), judgesPerProject = 2)

        assertEquals(10, changes.size)
        assertTrue(changes.values.all { it.size == 2 && it.distinct().size == 2 })
        val load = changes.values.flatten().groupingBy { it }.eachCount()
        assertTrue("load was $load", load.values.max() - load.values.min() <= 1)
    }

    @Test
    fun autoAssignKeepsExistingAssignmentsAndTopsUp() {
        val projects = listOf(project("a", judges = listOf("j1")), project("b", judges = listOf("j1", "j2")))
        val changes = Judging.autoAssign(projects, listOf("j1", "j2", "j3"), judgesPerProject = 2)

        assertEquals(mapOf("a" to listOf("j1", "j3")), changes) // j3 has the lightest load
    }

    @Test
    fun autoAssignDropsPeopleWhoAreNoLongerJudges() {
        val changes = Judging.autoAssign(listOf(project("a", judges = listOf("former"))), listOf("j1"), judgesPerProject = 1)
        assertEquals(mapOf("a" to listOf("j1")), changes)
    }

    @Test
    fun autoAssignCapsAtNumberOfJudges() {
        val changes = Judging.autoAssign(listOf(project("a")), listOf("j1", "j2"), judgesPerProject = 5)
        assertEquals(2, changes.getValue("a").size)
        assertTrue(Judging.autoAssign(listOf(project("a")), emptyList(), 2).isEmpty())
    }
}
