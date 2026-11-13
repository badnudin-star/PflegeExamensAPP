package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.AnswerEvaluationResult

/**
 * Vergleicht eine vom Nutzer bestätigte schriftliche Prüfungsantwort lokal (100 % offline)
 * mit den aus der Quelle extrahierten Kernpunkten.
 */
object AnswerEvaluator {

    fun evaluate(
        userAnswer: String,
        expectedKeyPoints: List<String>
    ): AnswerEvaluationResult {
        if (expectedKeyPoints.isEmpty()) {
            return AnswerEvaluationResult(
                matchedPoints = emptyList(),
                missingPoints = emptyList(),
                coveragePercent = 0
            )
        }

        val answerTokens = GermanTextAnalyzer.tokenizeKeywords(userAnswer, includeStems = true).toSet()
        val answerNorm = GermanTextAnalyzer.normalizeForMatching(userAnswer)

        if (answerTokens.isEmpty()) {
            return AnswerEvaluationResult(
                matchedPoints = emptyList(),
                missingPoints = expectedKeyPoints,
                coveragePercent = 0
            )
        }

        val matched = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for (point in expectedKeyPoints) {
            val distinctiveTerms = GermanTextAnalyzer.extractDistinctiveTerms(point)
                .ifEmpty { GermanTextAnalyzer.tokenizeKeywords(point, includeStems = false) }
                .distinct()

            if (distinctiveTerms.isEmpty()) {
                missing.add(point)
                continue
            }

            val hitCount = distinctiveTerms.count { term ->
                GermanTextAnalyzer.matchesTerm(term, answerTokens, answerNorm)
            }
            val ratio = hitCount.toDouble() / distinctiveTerms.size.toDouble()

            if (hitCount >= 1 && (ratio >= 0.38 || (distinctiveTerms.size == 1 && hitCount == 1))) {
                matched.add(point)
            } else {
                missing.add(point)
            }
        }

        val pct = ((matched.size.toDouble() / expectedKeyPoints.size.toDouble()) * 100.0).toInt()
        return AnswerEvaluationResult(
            matchedPoints = matched,
            missingPoints = missing,
            coveragePercent = pct
        )
    }
}
