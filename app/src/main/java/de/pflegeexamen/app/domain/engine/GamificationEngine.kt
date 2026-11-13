package de.pflegeexamen.app.domain.engine

import kotlin.math.roundToInt

data class StudyProfileState(
    val totalXp: Int = 0,
    val streakDays: Int = 0,
    val freeDaysRemaining: Int = 2,
    val dailyGoalPoints: Int = 20,
    val todayPoints: Int = 0,
    val lastActiveDayEpoch: Long = -1L
)

/**
 * Reine Kotlin-Gamification-Logik für schichtkompatible Lern-Streaks (inkl. Frei-Tagen
 * als Streak-Schutz), XP-Berechnung und Examens-Reife-Prozent.
 */
object GamificationEngine {

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    const val MAX_FREE_DAYS = 2

    fun epochDayOf(millis: Long): Long = millis / DAY_MILLIS

    fun applyActivity(
        current: StudyProfileState,
        xpEarned: Int,
        goalPointsEarned: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): StudyProfileState {
        val today = epochDayOf(nowMillis)
        var streak = current.streakDays
        var freeDays = current.freeDaysRemaining.coerceIn(0, MAX_FREE_DAYS)
        var todayPts = if (current.lastActiveDayEpoch == today) current.todayPoints else 0

        if (current.lastActiveDayEpoch < 0L) {
            streak = 1
        } else if (today > current.lastActiveDayEpoch) {
            val gapDays = (today - current.lastActiveDayEpoch - 1L).toInt()
            if (gapDays == 0) {
                streak += 1
            } else if (gapDays <= freeDays) {
                // Schicht-Schutz: verbraucht verfügbare Frei-Tage, Streak bleibt bestehen!
                freeDays -= gapDays
                streak += 1
            } else {
                streak = 1
            }
            // Alle 5 aktiven Lerntage regeneriert sich +1 Frei-Tag (bis max. 2)
            if (streak > 0 && streak % 5 == 0 && freeDays < MAX_FREE_DAYS) {
                freeDays += 1
            }
        }

        todayPts += goalPointsEarned.coerceAtLeast(0)

        return current.copy(
            totalXp = (current.totalXp + xpEarned.coerceAtLeast(0)),
            streakDays = streak,
            freeDaysRemaining = freeDays,
            todayPoints = todayPts,
            lastActiveDayEpoch = today
        )
    }

    /**
     * Berechnet eine ehrliche Examens-Reife (0..100 %) aus beherrschten Lernzetteln,
     * wiederholten/stabilen Karteikarten und bestätigten Fallbeispiel-Aufgaben.
     */
    fun calculateReadinessPercent(
        totalNotes: Int,
        masteredNotes: Int,
        reviewingNotes: Int,
        totalCards: Int,
        matureOrReviewedCards: Int,
        totalCaseQuestions: Int,
        confirmedCaseQuestions: Int
    ): Int {
        val components = mutableListOf<Double>()

        if (totalNotes > 0) {
            val noteScore = (masteredNotes * 1.0 + reviewingNotes * 0.45) / totalNotes.toDouble()
            components.add(noteScore.coerceIn(0.0, 1.0))
        }
        if (totalCards > 0) {
            val cardScore = matureOrReviewedCards.toDouble() / totalCards.toDouble()
            components.add(cardScore.coerceIn(0.0, 1.0))
        }
        if (totalCaseQuestions > 0) {
            val caseScore = confirmedCaseQuestions.toDouble() / totalCaseQuestions.toDouble()
            components.add(caseScore.coerceIn(0.0, 1.0))
        }

        if (components.isEmpty()) return 0
        return (components.average() * 100.0).roundToInt().coerceIn(0, 100)
    }
}
