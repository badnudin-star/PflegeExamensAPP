package de.pflegeexamen.app.domain.quiz

import de.pflegeexamen.app.domain.content.PublishedQuiz
import org.junit.Assert.*
import org.junit.Test

class QuizSessionTest {
    private val questions = listOf(
        PublishedQuiz("q1", "2026-11-13", "Thema", "Frage 1", listOf("A", "B", "C", "D"), 1, "Erklärung", listOf("Quelle")),
        PublishedQuiz("q2", "2026-11-13", "Thema", "Frage 2", listOf("A", "B", "C", "D"), 2, "Erklärung", listOf("Quelle"))
    )

    @Test fun `answer feedback advance completion and count`() {
        val start = QuizSession(quizFingerprint(questions))
        val feedback = start.answer(1, questions)
        assertEquals(listOf(1), feedback.answers)
        assertEquals(0, feedback.index)
        assertEquals(1, feedback.correctCount(questions))
        assertThrows(IllegalArgumentException::class.java) { feedback.answer(2, questions) }
        val second = feedback.advance(questions)
        assertEquals(1, second.index)
        val wrong = second.answer(0, questions)
        val finished = wrong.advance(questions)
        assertEquals(2, finished.index)
        assertEquals(1, finished.correctCount(questions))
        assertThrows(IllegalArgumentException::class.java) { finished.advance(questions) }
    }

    @Test fun `invalid answer and impossible states are rejected`() {
        val start = QuizSession(quizFingerprint(questions))
        assertThrows(IllegalArgumentException::class.java) { start.answer(4, questions) }
        assertThrows(IllegalArgumentException::class.java) { start.advance(questions) }
        assertFalse(start.copy(index = 2, answers = listOf(1)).isValidFor(questions))
        assertFalse(start.copy(index = 0, answers = listOf(1, 2)).isValidFor(questions))
        assertFalse(start.copy(answers = listOf(-1)).isValidFor(questions))
        assertFalse(start.copy(index = -1).isValidFor(questions))
    }

    @Test fun `review shows original positions and only wrong answers without modifying session`() {
        val start = QuizSession(quizFingerprint(questions))
        assertThrows(IllegalArgumentException::class.java) { start.reviewIndices(questions, false) }
        val first = start.answer(1, questions).advance(questions)
        assertThrows(IllegalArgumentException::class.java) { first.reviewIndices(questions, true) }
        val feedback = first.answer(0, questions)
        assertThrows(IllegalArgumentException::class.java) { feedback.reviewIndices(questions, false) }
        val completed = feedback.advance(questions)
        assertEquals(listOf(0, 1), completed.reviewIndices(questions, false))
        assertEquals(listOf(1), completed.reviewIndices(questions, true))
        assertEquals(listOf(1, 0), completed.answers)
        assertEquals(1, completed.correctCount(questions))
        assertThrows(IllegalArgumentException::class.java) { completed.reviewIndices(questions.reversed(), false) }
        assertThrows(IllegalArgumentException::class.java) { completed.copy(answers = listOf(1)).reviewIndices(questions, false) }
    }

    @Test fun `review handles all correct all wrong and empty sessions`() {
        val fingerprint = quizFingerprint(questions)
        assertEquals(emptyList<Int>(), QuizSession(fingerprint, 2, listOf(1, 2)).reviewIndices(questions, true))
        assertEquals(listOf(0, 1), QuizSession(fingerprint, 2, listOf(0, 0)).reviewIndices(questions, true))
        val empty = emptyList<PublishedQuiz>()
        assertThrows(IllegalArgumentException::class.java) { QuizSession(quizFingerprint(empty)).reviewIndices(empty, false) }
    }

    @Test fun `topic results reflect only the completed local attempt in question order`() {
        val mixed = listOf(
            questions[0].copy(topic = "Thema B"), questions[1].copy(topic = "Thema A"),
            questions[0].copy(id = "q3", topic = "Thema B", correctIndex = 3),
            questions[1].copy(id = "q4", topic = "Thema A", correctIndex = 0)
        )
        val start = QuizSession(quizFingerprint(mixed))
        assertThrows(IllegalArgumentException::class.java) { start.topicResults(mixed) }
        val finished = start.answer(1, mixed).advance(mixed).answer(0, mixed).advance(mixed)
            .answer(1, mixed).advance(mixed).answer(0, mixed).advance(mixed)
        assertEquals(listOf(QuizTopicResult("Thema B", 1, 2), QuizTopicResult("Thema A", 1, 2)), finished.topicResults(mixed))
        assertEquals(2, finished.correctCount(mixed))
        assertEquals(listOf(1, 0, 1, 0), finished.answers)
        assertThrows(IllegalArgumentException::class.java) { finished.topicResults(mixed.reversed()) }
        val empty = emptyList<PublishedQuiz>()
        assertThrows(IllegalArgumentException::class.java) { QuizSession(quizFingerprint(empty)).topicResults(empty) }
    }

    @Test fun `topic results handle all correct and all incorrect`() {
        val fingerprint = quizFingerprint(questions)
        assertEquals(listOf(QuizTopicResult("Thema", 2, 2)), QuizSession(fingerprint, 2, listOf(1, 2)).topicResults(questions))
        assertEquals(listOf(QuizTopicResult("Thema", 0, 2)), QuizSession(fingerprint, 2, listOf(0, 0)).topicResults(questions))
    }

    @Test fun `content changes or question reorder invalidate state`() {
        val original = QuizSession(quizFingerprint(questions)).answer(1, questions)
        assertTrue(original.isValidFor(questions))
        assertFalse(original.isValidFor(questions.reversed()))
        assertFalse(original.isValidFor(listOf(questions[0].copy(correctIndex = 0), questions[1])))
        assertFalse(original.isValidFor(listOf(questions[0].copy(explanation = "Korrigiert"), questions[1])))
    }
}
