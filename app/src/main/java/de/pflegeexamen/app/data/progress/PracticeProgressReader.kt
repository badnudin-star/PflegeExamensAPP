package de.pflegeexamen.app.data.progress

import android.content.SharedPreferences
import de.pflegeexamen.app.domain.quiz.QuizSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface PracticeReadResult {
    data class Unverified(val snapshot: PracticeProgressSnapshot) : PracticeReadResult
    data object InvalidData : PracticeReadResult
    data object Unavailable : PracticeReadResult
}

/**
 * Experimental, read-only adapter; not used by the app or a user-facing backup.
 * SharedPreferences can silently treat an unreadable file as empty, and reading three
 * preferences sequentially is not an atomic snapshot. Unverified therefore does NOT prove
 * completeness, disk durability or cross-store consistency. Do not use it as a backup.
 */
internal object PracticeProgressReader {
    private const val CARD_KEY = "learned_card_ids"
    private const val CASE_KEY = "completed_case_ids"
    private const val MAX_RAW_QUIZ_CHARS = 64 * 1024

    fun read(cards: SharedPreferences, cases: SharedPreferences, quizzes: SharedPreferences): PracticeReadResult = try {
        readMaps(cards.all, cases.all, quizzes.all)
    } catch (_: Exception) {
        PracticeReadResult.Unavailable
    }

    /** Any unknown or malformed data rejects the entire capture, rather than discarding it. */
    internal fun readMaps(cards: Map<String, *>, cases: Map<String, *>, quizzes: Map<String, *>): PracticeReadResult = try {
        val cardIds = ids(cards, CARD_KEY)
        val caseIds = ids(cases, CASE_KEY)
        require(quizzes.size <= PracticeProgressFormat.MAX_DAYS)
        var totalChars = 0L
        val quizStates = quizzes.map { (date, value) ->
            val raw = value as? String ?: throw IllegalArgumentException()
            require(raw.length <= MAX_RAW_QUIZ_CHARS)
            totalChars += raw.length.toLong()
            require(totalChars <= PracticeProgressFormat.MAX_BYTES)
            val session = Json.decodeFromString<QuizSession>(raw)
            // Only accept the form currently written by QuizProgressStore. This also
            // rejects duplicate JSON keys instead of silently losing their values.
            require(Json.encodeToString(session) == raw)
            PracticeQuizSnapshot(date, session)
        }.sortedBy { it.date }
        val snapshot = PracticeProgressSnapshot(cardIds, caseIds, quizStates)
        // Also check the overall encoded byte limit; never truncate oversize input.
        PracticeProgressFormat.encode(snapshot)
        PracticeReadResult.Unverified(snapshot)
    } catch (_: Exception) {
        PracticeReadResult.InvalidData
    }

    private fun ids(values: Map<String, *>, key: String): List<String> {
        require(values.keys.all { it == key })
        if (!values.containsKey(key)) return emptyList()
        val set = values[key] as? Set<*> ?: throw IllegalArgumentException()
        require(set.size <= PracticeProgressFormat.MAX_IDS && set.all { it is String })
        return set.map { it as String }.sorted()
    }
}
