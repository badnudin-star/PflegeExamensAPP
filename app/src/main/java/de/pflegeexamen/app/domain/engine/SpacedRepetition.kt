package de.pflegeexamen.app.domain.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Klassischer SM-2 Spaced-Repetition-Algorithmus für nachhaltige Prüfungsvorbereitung.
 * quality:
 *  1 = Nochmal (nicht gewusst)
 *  3 = Schwer (mit Mühe erinnert)
 *  4 = Gut (sicher gewusst)
 *  5 = Leicht (sofort abrufbar)
 */
object SpacedRepetition {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class ReviewSchedule(
        val repetitions: Int,
        val intervalDays: Int,
        val easeFactor: Float,
        val nextReviewAt: Long
    )

    fun calculateNext(
        previousRepetitions: Int,
        previousIntervalDays: Int,
        previousEaseFactor: Float,
        quality: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): ReviewSchedule {
        val clampedQuality = quality.coerceIn(0, 5)
        val newEase = max(
            1.3f,
            previousEaseFactor + (0.1f - (5 - clampedQuality) * (0.08f + (5 - clampedQuality) * 0.02f))
        )

        if (clampedQuality < 3) {
            // Bei Unsicherheit/Fehlern Karte wieder kurzfristig vorlegen
            return ReviewSchedule(
                repetitions = 0,
                intervalDays = 0,
                easeFactor = newEase,
                nextReviewAt = nowMillis + (10L * 60L * 1000L) // in 10 Minuten erneut prüfen
            )
        }

        val newReps = previousRepetitions + 1
        val newInterval = when (newReps) {
            1 -> 1
            2 -> 6
            else -> (previousIntervalDays.coerceAtLeast(1) * newEase).roundToInt().coerceAtLeast(1)
        }

        return ReviewSchedule(
            repetitions = newReps,
            intervalDays = newInterval,
            easeFactor = newEase,
            nextReviewAt = nowMillis + newInterval * DAY_MS
        )
    }
}
