package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.quiz.QuizSession
import org.junit.Assert.*
import org.junit.Test

class PracticeProgressFormatTest {
    private val session = QuizSession("a".repeat(64), 1, listOf(2, 0))
    private val snapshot = PracticeProgressSnapshot(
        listOf("Karte-2", "Karte-1"), listOf("Fall-1"),
        listOf(PracticeQuizSnapshot("2026-11-13", session))
    )
    private fun decode(text: String) = PracticeProgressFormat.decode(text.toByteArray(Charsets.UTF_8))

    @Test fun `partial snapshot round trips with deterministic output and no extras`() {
        val data = PracticeProgressFormat.encode(snapshot)
        val restored = PracticeProgressFormat.decode(data)!!
        assertEquals(snapshot.copy(learnedCardIds = listOf("Karte-1", "Karte-2")), restored)
        assertArrayEquals(data, PracticeProgressFormat.encode(restored))
        assertEquals(PracticeProgressSnapshot(emptyList(), emptyList(), emptyList()),
            PracticeProgressFormat.decode(PracticeProgressFormat.encode(PracticeProgressSnapshot(emptyList(), emptyList(), emptyList()))))
        // Only the explicitly supplied IDs and local quiz state are serialized.
        val json = data.toString(Charsets.UTF_8)
        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("ranking", ignoreCase = true))
    }

    @Test fun `unicode and brackets in ids survive without confusing nesting check`() {
        val original = snapshot.copy(learnedCardIds = listOf("ä[]{}\\\"", "🙂"))
        assertEquals(original.learnedCardIds.sorted(), PracticeProgressFormat.decode(PracticeProgressFormat.encode(original))!!.learnedCardIds)
    }

    @Test fun `malformed types versions duplicate entries and invalid sessions are rejected`() {
        val good = PracticeProgressFormat.encode(snapshot).toString(Charsets.UTF_8)
        val bad = listOf(
            good.replace("pflege-practice-progress", "different"),
            good.replace(",1,", ",2,"),
            good.replace("[\"Karte-1\",\"Karte-2\"]", "[\"Karte-1\",\"Karte-1\"]"),
            good.replace("2026-11-13", "2026-02-29"),
            good.replace("2026-11-13", "2026-1-13"),
            good.replace("\"${"a".repeat(64)}\"", "\"${"G".repeat(64)}\""),
            good.replace(",1,[2,0]", ",-1,[2,0]"),
            good.replace(",1,[2,0]", ",3,[2,0]"),
            good.replace("[2,0]", "[4,0]"),
            good.replace("[2,0]", "[\"2\",0]"),
            good.dropLast(1),
            "$good true",
            "{\"data\":$good}",
            "[[[[[[[[[0]]]]]]]]]"
        )
        bad.forEach { assertNull("accepted: $it", decode(it)) }
        val dayJson = "[\"2026-11-13\",\"${"a".repeat(64)}\",1,[2,0]]"
        assertNull(decode("[\"pflege-practice-progress\",1,[],[],[$dayJson,$dayJson]]"))
        val duplicateDays = snapshot.copy(quizzes = listOf(snapshot.quizzes[0], snapshot.quizzes[0]))
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressFormat.encode(duplicateDays) }
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressFormat.encode(snapshot.copy(completedCaseIds = listOf(" "))) }
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressFormat.encode(snapshot.copy(learnedCardIds = listOf("x\u0000"))) }
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressFormat.encode(snapshot.copy(learnedCardIds = listOf("x".repeat(513)))) }
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressFormat.encode(snapshot.copy(learnedCardIds = listOf("\uD800"))) }
    }

    @Test fun `bounded bytes and malformed utf8 are rejected`() {
        assertNull(PracticeProgressFormat.decode(ByteArray(PracticeProgressFormat.MAX_BYTES + 1)))
        assertNull(PracticeProgressFormat.decode(byteArrayOf(0xc3.toByte(), 0x28)))
        assertThrows(IllegalArgumentException::class.java) {
            PracticeProgressFormat.encode(snapshot.copy(learnedCardIds = (0..20_000).map { "id$it" }))
        }
    }

    @Test fun `syntax validation alone does not verify that quiz questions match`() {
        val restored = PracticeProgressFormat.decode(PracticeProgressFormat.encode(snapshot))!!
        // QuizSession.isValidFor must still be checked against the exact questions before use.
        assertFalse(restored.quizzes.single().session.isValidFor(emptyList()))
    }
}
