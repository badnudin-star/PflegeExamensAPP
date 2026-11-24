package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.quiz.QuizSession
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Experimental, partial format: no Room data, catalog, archive, credentials or ranked actions.
 * This codec performs no storage or I/O and is not connected to an import or export screen. */
internal data class PracticeProgressSnapshot(
    val learnedCardIds: List<String>,
    val completedCaseIds: List<String>,
    val quizzes: List<PracticeQuizSnapshot>
)

internal data class PracticeQuizSnapshot(val date: String, val session: QuizSession)

internal object PracticeProgressFormat {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_IDS = 20_000
    private const val MAX_ID_LENGTH = 512
    private const val MAX_DAYS = 3_660
    private const val MAX_ANSWERS = 10_000
    private const val FORMAT = "pflege-practice-progress"
    private const val VERSION = 1
    private val fingerprint = Regex("[0-9a-f]{64}")

    /** Throws rather than silently truncating invalid or oversized data. */
    fun encode(snapshot: PracticeProgressSnapshot): ByteArray {
        require(valid(snapshot))
        val data = JsonArray(listOf(
            JsonPrimitive(FORMAT), JsonPrimitive(VERSION),
            JsonArray(snapshot.learnedCardIds.sorted().map(::JsonPrimitive)),
            JsonArray(snapshot.completedCaseIds.sorted().map(::JsonPrimitive)),
            JsonArray(snapshot.quizzes.sortedBy { it.date }.map { quiz ->
                JsonArray(listOf(
                    JsonPrimitive(quiz.date), JsonPrimitive(quiz.session.fingerprint),
                    JsonPrimitive(quiz.session.index), JsonArray(quiz.session.answers.map(::JsonPrimitive))
                ))
            })
        ))
        return data.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    /** Accepts only the bounded array-based version 1 format, without touching persisted state.
     * Callers reading streams must enforce MAX_BYTES while reading, before allocating the array. */
    fun decode(bytes: ByteArray): PracticeProgressSnapshot? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (!boundedNesting(text)) return null
            val root = Json.parseToJsonElement(text).array(5)
            require(root[0].string() == FORMAT && root[1].integer() == VERSION)
            val cards = root[2].arrayAtMost(MAX_IDS).map { it.string() }
            val cases = root[3].arrayAtMost(MAX_IDS).map { it.string() }
            val quizzes = root[4].arrayAtMost(MAX_DAYS).map { entry ->
                val fields = entry.array(4)
                PracticeQuizSnapshot(fields[0].string(), QuizSession(
                    fields[1].string(), fields[2].integer(), fields[3].arrayAtMost(MAX_ANSWERS).map { it.integer() }
                ))
            }
            PracticeProgressSnapshot(cards, cases, quizzes).takeIf(::valid)
        } catch (_: Exception) {
            null
        }
    }

    internal fun valid(snapshot: PracticeProgressSnapshot): Boolean =
        validIds(snapshot.learnedCardIds) && validIds(snapshot.completedCaseIds) &&
            snapshot.quizzes.size <= MAX_DAYS &&
            snapshot.quizzes.map { it.date }.toSet().size == snapshot.quizzes.size &&
            snapshot.quizzes.all { quiz ->
                validDate(quiz.date) && fingerprint.matches(quiz.session.fingerprint) &&
                    quiz.session.answers.size <= MAX_ANSWERS &&
                    quiz.session.index >= 0 &&
                    (quiz.session.index == quiz.session.answers.size ||
                        quiz.session.index == quiz.session.answers.size - 1) &&
                    quiz.session.answers.all { it in 0..3 }
            }

    private fun validIds(ids: List<String>): Boolean =
        ids.size <= MAX_IDS && ids.toSet().size == ids.size && ids.all(::validId)

    private fun validId(id: String): Boolean {
        if (id.isBlank() || id.length > MAX_ID_LENGTH) return false
        var index = 0
        while (index < id.length) {
            val ch = id[index]
            if (Character.isISOControl(ch) || Character.isLowSurrogate(ch)) return false
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 >= id.length || !Character.isLowSurrogate(id[index + 1])) return false
                index++
            }
            index++
        }
        return true
    }

    private fun validDate(value: String): Boolean =
        value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
            runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)

    private fun JsonElement.array(size: Int): JsonArray = (this as? JsonArray)?.also { require(it.size == size) }
        ?: throw IllegalArgumentException("Expected array")
    private fun JsonElement.arrayAtMost(size: Int): JsonArray = (this as? JsonArray)?.also { require(it.size <= size) }
        ?: throw IllegalArgumentException("Expected array")
    private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Expected string")
    private fun JsonElement.integer(): Int = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: throw IllegalArgumentException("Expected integer")

    // Avoid passing deeply nested untrusted JSON to the parser. Quoted brackets do not count.
    private fun boundedNesting(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '[', '{' -> { depth++; if (depth > 8) return false }
                ']', '}' -> { depth--; if (depth < 0) return false }
            }
        }
        return !inString && depth == 0
    }
}
