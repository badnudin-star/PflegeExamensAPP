package de.pflegeexamen.app.domain.quiz

import de.pflegeexamen.app.domain.content.PublishedQuiz
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Summary of this practice session, not a measurement of mastery or a ranking result. */
data class QuizTopicResult(val topic: String, val correct: Int, val total: Int)

/** Local practice only. Answers and scores are never submitted to a ranking. */
@Serializable
data class QuizSession(val fingerprint: String, val index: Int = 0, val answers: List<Int> = emptyList()) {
    fun isValidFor(questions: List<PublishedQuiz>): Boolean =
        fingerprint == quizFingerprint(questions) &&
            index in 0..questions.size && answers.size <= questions.size &&
            (answers.size == index || (index < questions.size && answers.size == index + 1)) &&
            answers.all { it in 0..3 }

    fun answer(option: Int, questions: List<PublishedQuiz>): QuizSession {
        require(isValidFor(questions) && index < questions.size && answers.size == index && option in 0..3)
        return copy(answers = answers + option)
    }

    fun advance(questions: List<PublishedQuiz>): QuizSession {
        require(isValidFor(questions) && index < questions.size && answers.size == index + 1)
        return copy(index = index + 1)
    }

    /** Results are available only after completing the practice session. */
    fun reviewIndices(questions: List<PublishedQuiz>, onlyWrong: Boolean): List<Int> {
        require(isValidFor(questions) && index == questions.size && questions.isNotEmpty())
        return questions.indices.filter { !onlyWrong || answers[it] != questions[it].correctIndex }
    }

    /** Available only when the complete local quiz has been answered. Preserves topic order. */
    fun topicResults(questions: List<PublishedQuiz>): List<QuizTopicResult> {
        require(isValidFor(questions) && index == questions.size && questions.isNotEmpty())
        return questions.indices.groupBy { questions[it].topic }.map { (topic, indices) ->
            QuizTopicResult(topic, indices.count { answers[it] == questions[it].correctIndex }, indices.size)
        }
    }

    fun correctCount(questions: List<PublishedQuiz>): Int {
        require(isValidFor(questions))
        return answers.indices.count { answers[it] == questions[it].correctIndex }
    }
}

/** Includes full question content, so corrected or reordered quizzes never reuse stale answers. */
fun quizFingerprint(questions: List<PublishedQuiz>): String {
    val bytes = Json.encodeToString(questions).toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
