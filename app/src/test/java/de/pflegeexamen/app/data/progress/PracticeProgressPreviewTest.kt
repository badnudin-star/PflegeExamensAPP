package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.content.PublishedQuiz
import de.pflegeexamen.app.domain.quiz.QuizSession
import de.pflegeexamen.app.domain.quiz.quizFingerprint
import org.junit.Assert.*
import org.junit.Test

class PracticeProgressPreviewTest {
    private fun questions(date: String) = listOf(
        PublishedQuiz("q-$date", date, "Test", "Frage?", listOf("A", "B", "C", "D"), 1, "Erklärung", listOf("Test"))
    )
    private fun entry(date: String): PracticeQuizSnapshot {
        val quiz = questions(date)
        return PracticeQuizSnapshot(date, QuizSession(quizFingerprint(quiz), 0, listOf(1)))
    }
    private fun bytes(snapshot: PracticeProgressSnapshot) = PracticeProgressFormat.encode(snapshot)
    private val empty = PracticeProgressSnapshot(emptyList(), emptyList(), emptyList())

    @Test fun `compares overlaps missing catalog entries quiz conflicts and device only data without changes`() {
        val days = (1..4).map { "2026-11-0$it" }
        val importedCards = mutableListOf("known", "missing", "both")
        val currentCards = mutableListOf("both", "local-only")
        val fromFile = PracticeProgressSnapshot(importedCards, listOf("case-known", "case-missing"), days.map(::entry))
        val local = PracticeProgressSnapshot(currentCards, listOf("case-local"), listOf(
            entry(days[0]), entry(days[1]).copy(session = entry(days[1]).session.copy(answers = listOf(0))), entry("2026-11-05")
        ))
        val data = bytes(fromFile)
        val available = mapOf(
            days[0] to questions(days[0]),
            days[1] to questions(days[1]).map { it.copy(question = "Geändert?") },
            days[2] to emptyList(),
            days[3] to questions(days[0]) // wrong date is unavailable, not a match
        )
        val result = PracticeProgressPreview.compare(data, local, setOf("known", "both"), setOf("case-known"), available)
        val preview = (result as PreviewResult.Ready).preview
        assertEquals(MarkingPreview(3, 1, 1, 1), preview.cards)
        assertEquals(MarkingPreview(2, 0, 1, 1), preview.cases)
        assertEquals(listOf(
            QuizPreview(days[0], QuizContentMatch.MATCHES, QuizDeviceMatch.IDENTICAL),
            QuizPreview(days[1], QuizContentMatch.DIFFERENT_OR_INVALID, QuizDeviceMatch.DIFFERENT),
            QuizPreview(days[2], QuizContentMatch.UNAVAILABLE, QuizDeviceMatch.NOT_PRESENT),
            QuizPreview(days[3], QuizContentMatch.UNAVAILABLE, QuizDeviceMatch.NOT_PRESENT)
        ), preview.quizzes)
        assertEquals(1, preview.quizDaysOnlyOnDevice)
        assertEquals(listOf("known", "missing", "both"), importedCards)
        assertEquals(listOf("both", "local-only"), currentCards)
        assertArrayEquals(data, bytes(fromFile))
    }

    @Test fun `unavailable state is not treated as empty and empty catalog is distinct`() {
        val imported = PracticeProgressSnapshot(listOf("c"), listOf("f"), listOf(entry("2026-11-01")))
        val unknown = (PracticeProgressPreview.compare(bytes(imported), null, null, null, emptyMap()) as PreviewResult.Ready).preview
        assertEquals(MarkingPreview(1, null, null, null), unknown.cards)
        assertEquals(MarkingPreview(1, null, null, null), unknown.cases)
        assertEquals(QuizDeviceMatch.UNAVAILABLE, unknown.quizzes.single().device)
        assertEquals(QuizContentMatch.UNAVAILABLE, unknown.quizzes.single().content)
        assertNull(unknown.quizDaysOnlyOnDevice)
        val knownEmpty = (PracticeProgressPreview.compare(bytes(imported), empty, emptySet(), emptySet(), emptyMap()) as PreviewResult.Ready).preview
        assertEquals(MarkingPreview(1, 0, 0, 1), knownEmpty.cards)
        assertEquals(QuizDeviceMatch.NOT_PRESENT, knownEmpty.quizzes.single().device)
        assertEquals(0, knownEmpty.quizDaysOnlyOnDevice)
    }

    @Test fun `rejects invalid file or local snapshot instead of treating as empty`() {
        val invalidLocal = empty.copy(learnedCardIds = listOf("duplicate", "duplicate"))
        assertEquals(PreviewResult.InvalidFile, PracticeProgressPreview.compare("{}".toByteArray(), invalidLocal, null, null, emptyMap()))
        assertEquals(PreviewResult.InvalidDeviceSnapshot, PracticeProgressPreview.compare(bytes(empty), invalidLocal, null, null, emptyMap()))
    }

    @Test fun `fingerprint match alone does not make an impossible position valid`() {
        val date = "2026-11-01"
        val impossible = entry(date).let { it.copy(session = it.session.copy(index = 2, answers = listOf(0, 1))) }
        val file = empty.copy(quizzes = listOf(impossible))
        val preview = (PracticeProgressPreview.compare(bytes(file), empty, null, null, mapOf(date to questions(date))) as PreviewResult.Ready).preview
        assertEquals(QuizContentMatch.DIFFERENT_OR_INVALID, preview.quizzes.single().content)
    }
}
