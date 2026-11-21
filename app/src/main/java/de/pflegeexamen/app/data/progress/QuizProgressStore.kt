package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.content.PublishedQuiz
import de.pflegeexamen.app.domain.quiz.QuizSession
import de.pflegeexamen.app.domain.quiz.quizFingerprint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface QuizProgressPersistence {
    fun load(date: String): String?
    fun save(date: String, value: String)
    fun remove(date: String)
}

/** Separate local practice state per date; invalid or changed content starts a new session. */
class QuizProgressStore(private val persistence: QuizProgressPersistence) {
    fun load(date: String, questions: List<PublishedQuiz>): QuizSession {
        val saved = persistence.load(date)?.let { runCatching { Json.decodeFromString<QuizSession>(it) }.getOrNull() }
        return saved?.takeIf { it.isValidFor(questions) } ?: QuizSession(quizFingerprint(questions))
    }

    fun save(date: String, questions: List<PublishedQuiz>, session: QuizSession) {
        require(date.isNotBlank() && session.isValidFor(questions))
        persistence.save(date, Json.encodeToString(session))
    }

    fun reset(date: String) { persistence.remove(date) }
}
