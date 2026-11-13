package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.model.ExamRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamificationAndCatalogTest {

    @Test
    fun `applyActivity increments streak and protects shift gaps with Frei-Tage`() {
        val dayMs = 24L * 60L * 60L * 1000L
        var state = StudyProfileState(
            totalXp = 0,
            streakDays = 0,
            freeDaysRemaining = 2,
            dailyGoalPoints = 20,
            todayPoints = 0,
            lastActiveDayEpoch = -1L
        )

        // First active day (epoch day 100)
        state = GamificationEngine.applyActivity(state, xpEarned = 20, goalPointsEarned = 10, nowMillis = 100L * dayMs)
        assertEquals(1, state.streakDays)
        assertEquals(20, state.totalXp)
        assertEquals(10, state.todayPoints)

        // Next consecutive day (epoch day 101)
        state = GamificationEngine.applyActivity(state, xpEarned = 20, goalPointsEarned = 15, nowMillis = 101L * dayMs)
        assertEquals(2, state.streakDays)
        assertEquals(2, state.freeDaysRemaining)
        assertEquals(15, state.todayPoints)

        // Miss 1 day (shift day: epoch day 102 skipped -> active on day 103)
        state = GamificationEngine.applyActivity(state, xpEarned = 10, goalPointsEarned = 5, nowMillis = 103L * dayMs)
        assertEquals(3, state.streakDays) // streak preserved!
        assertEquals(1, state.freeDaysRemaining) // consumed 1 Frei-Tag

        // Miss 4 days -> exceeds remaining free day -> streak resets to 1
        state = GamificationEngine.applyActivity(state, xpEarned = 10, goalPointsEarned = 5, nowMillis = 108L * dayMs)
        assertEquals(1, state.streakDays)
    }

    @Test
    fun `rank lookup and readiness calculation compute cleanly`() {
        assertEquals(ExamRank.ORIENTATION, ExamRank.forXp(0))
        assertEquals(ExamRank.SPECIALIZATION, ExamRank.forXp(500))
        assertEquals(ExamRank.EXAM_READY, ExamRank.forXp(1800))

        val readiness = GamificationEngine.calculateReadinessPercent(
            totalNotes = 4,
            masteredNotes = 2,
            reviewingNotes = 0,
            totalCards = 10,
            matureOrReviewedCards = 5,
            totalCaseQuestions = 6,
            confirmedCaseQuestions = 3
        )
        assertEquals(50, readiness)
    }

    @Test
    fun `default catalogs contain 3-day Fruehjahr 2027 topics and 17 Landes-AG Dez 2025 operators`() {
        assertEquals(5, DefaultCatalogs.defaultCategories.size)
        assertEquals(42, DefaultCatalogs.defaultPreparations.size)
        assertEquals(14, DefaultCatalogs.defaultPreparations.count { it.examDayTag == "Tag 1" })
        assertEquals(13, DefaultCatalogs.defaultPreparations.count { it.examDayTag == "Tag 2" })
        assertEquals(15, DefaultCatalogs.defaultPreparations.count { it.examDayTag == "Tag 3" })

        val clinicalTopics = DefaultCatalogs.defaultPreparations.filter { it.isClinicalPicture }
        assertTrue(clinicalTopics.size >= 14)
        clinicalTopics.forEach { prep ->
            assertEquals("Anatomie & Physiologie", prep.focusParameters().first())
        }

        assertEquals(17, DefaultCatalogs.defaultOperators.size)
        val levels = DefaultCatalogs.defaultOperators.map { it.requirementLevel }.toSet()
        assertTrue("Taxonomie WV" in levels)
        assertTrue("Taxonomie ASB" in levels)

        val noCaseNames = DefaultCatalogs.defaultOperators
            .filter { it.caseRelationRule == CaseRelationRule.NO_CASE_RELATION }
            .map { it.name }
        assertTrue("Definieren" in noCaseNames)
        assertTrue("Erklären" in noCaseNames)
        assertTrue("Begründen" in noCaseNames)

        DefaultCatalogs.defaultOperators.forEach { op ->
            assertTrue(op.exampleQuestion.isNotBlank())
            assertTrue(op.expectationHorizon.isNotBlank())
            assertTrue(op.gradingRubric.isNotBlank())
        }
    }
}
