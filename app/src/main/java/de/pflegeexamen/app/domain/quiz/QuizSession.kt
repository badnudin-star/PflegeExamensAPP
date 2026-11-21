package de.pflegeexamen.app.domain.quiz

import de.pflegeexamen.app.domain.content.PublishedQuiz
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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
