package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.content.PublishedQuiz
import org.junit.Assert.*
import org.junit.Test

class QuizProgressStoreTest {
    private class MemoryPersistence : QuizProgressPersistence {
        val values = mutableMapOf<String, String>()
        override fun load(date: String): String? = values[date]
        override fun save(date: String, value: String) { values[date] = value }
        override fun remove(date: String) { values.remove(date) }
    }
    private val date = "2026-11-13"
    private val quiz = listOf(PublishedQuiz("q1", date, "Thema", "Frage", listOf("A", "B", "C", "D"), 1, "Erklärung", listOf("Quelle")))

    @Test fun `save restore feedback and completed state across recreations`() {
        val memory = MemoryPersistence()
        val store = QuizProgressStore(memory)
        val answered = store.load(date, quiz).answer(1, quiz)
        store.save(date, quiz, answered)
        val reopened = QuizProgressStore(memory).load(date, quiz)
        assertEquals(answered, reopened)
        val finished = reopened.advance(quiz)
        store.save(date, quiz, finished)
        assertEquals(finished, QuizProgressStore(memory).load(date, quiz))
        assertEquals(1, finished.correctCount(quiz))
    }

    @Test fun `days are independent and reset affects only selected day`() {
        val memory = MemoryPersistence()
        val store = QuizProgressStore(memory)
        store.save(date, quiz, store.load(date, quiz).answer(1, quiz))
        val otherDate = "2026-11-14"
        val otherQuiz = listOf(quiz[0].copy(id = "q2", published = otherDate))
        store.save(otherDate, otherQuiz, store.load(otherDate, otherQuiz).answer(0, otherQuiz))
        store.reset(date)
        assertTrue(store.load(date, quiz).answers.isEmpty())
        assertEquals(listOf(0), store.load(otherDate, otherQuiz).answers)
    }

    @Test fun `malformed impossible and stale persisted data fall back safely`() {
        val memory = MemoryPersistence()
        val store = QuizProgressStore(memory)
        memory.values[date] = "not json"
        assertEquals(0, store.load(date, quiz).index)
        val good = store.load(date, quiz).answer(1, quiz)
        assertThrows(IllegalArgumentException::class.java) { store.save(date, quiz, good.copy(index = 2)) }
        store.save(date, quiz, good)
        val corrected = listOf(quiz[0].copy(correctIndex = 0))
        assertTrue(store.load(date, corrected).answers.isEmpty())
        assertNotEquals(good.fingerprint, store.load(date, corrected).fingerprint)
    }
}
