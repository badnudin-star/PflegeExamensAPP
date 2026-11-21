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

    @Test fun `content changes or question reorder invalidate state`() {
        val original = QuizSession(quizFingerprint(questions)).answer(1, questions)
        assertTrue(original.isValidFor(questions))
        assertFalse(original.isValidFor(questions.reversed()))
        assertFalse(original.isValidFor(listOf(questions[0].copy(correctIndex = 0), questions[1])))
        assertFalse(original.isValidFor(listOf(questions[0].copy(explanation = "Korrigiert"), questions[1])))
    }
}
