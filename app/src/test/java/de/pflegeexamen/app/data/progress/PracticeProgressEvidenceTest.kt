package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.quiz.QuizSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PracticeProgressEvidenceTest {
    private val session = QuizSession("a".repeat(64), 0, listOf(1))
    private val raw = Json.encodeToString(session)
    private fun xml(value: String) = value.toByteArray(Charsets.UTF_8)
    private val cards = PreferenceEvidence(mapOf("learned_card_ids" to setOf("c1", "c2")),
        xml("<map><set name=\"learned_card_ids\"><string>c1</string><string>c2</string></set></map>"))
    private val cases = PreferenceEvidence(mapOf("completed_case_ids" to setOf("f1")),
        xml("<map><set name=\"completed_case_ids\"><string>f1</string></set></map>"))
    private val quizzes = PreferenceEvidence(mapOf("2026-11-13" to raw),
        xml("<map><string name=\"2026-11-13\">$raw</string></map>"))

    @Test fun `matching synthetic evidence yields only an unverified capture`() {
        val result = PracticeProgressEvidence.compare(cards, cases, quizzes)
        assertEquals(PracticeReadResult.Unverified(PracticeProgressSnapshot(listOf("c1", "c2"), listOf("f1"),
            listOf(PracticeQuizSnapshot("2026-11-13", session)))), result)
        assertEquals(setOf("c1", "c2"), cards.memory["learned_card_ids"])
        assertEquals(raw, quizzes.memory["2026-11-13"])
    }

    @Test fun `missing malformed mismatched or unsupported evidence never becomes empty success`() {
        assertEquals(PracticeReadResult.Unavailable, PracticeProgressEvidence.compare(cards.copy(xml = null), cases, quizzes))
        assertEquals(PracticeReadResult.Unavailable, PracticeProgressEvidence.compare(cards, cases.copy(xml = null), quizzes))
        assertEquals(PracticeReadResult.Unavailable, PracticeProgressEvidence.compare(cards, cases, quizzes.copy(xml = null)))
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(cards.copy(xml = xml("<map>")), cases, quizzes))
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(cards.copy(memory = emptyMap<String, Any>()), cases, quizzes))
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(cards, cases.copy(xml = xml("<map/>")), quizzes))
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(cards, cases, quizzes.copy(xml = byteArrayOf(0xc3.toByte(), 0x28))))
        val unknown = PreferenceEvidence(mapOf("extra" to "v"), xml("<map><string name=\"extra\">v</string></map>"))
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(unknown, cases, quizzes))
    }

    @Test fun `a changed map or changed bytes after capture are not silently accepted`() {
        val mutable = mutableMapOf<String, Any>("learned_card_ids" to setOf("c1", "c2"))
        val evidence = PreferenceEvidence(mutable, cards.xml!!.copyOf())
        assertTrue(PracticeProgressEvidence.compare(evidence, cases, quizzes) is PracticeReadResult.Unverified)
        mutable["learned_card_ids"] = setOf("c1")
        assertEquals(PracticeReadResult.InvalidData, PracticeProgressEvidence.compare(evidence, cases, quizzes))
    }
}
