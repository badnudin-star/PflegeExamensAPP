package de.pflegeexamen.app.data.progress

import android.content.SharedPreferences
import de.pflegeexamen.app.domain.quiz.QuizSession
import java.lang.reflect.Proxy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PracticeProgressReaderTest {
    private val session = QuizSession("a".repeat(64), 0, listOf(1))
    private val raw = Json.encodeToString(session)
    private fun read(cards: Map<String, *> = emptyMap<String, Any>(), cases: Map<String, *> = emptyMap<String, Any>(),
                     quizzes: Map<String, *> = emptyMap<String, Any>()) = PracticeProgressReader.readMaps(cards, cases, quizzes)

    @Test fun `all supported preferences are captured without changing input`() {
        val cards = mutableSetOf("c2", "c1")
        val cases = mutableSetOf("f1")
        val quiz = mutableMapOf("2026-11-13" to raw)
        val result = read(mapOf("learned_card_ids" to cards), mapOf("completed_case_ids" to cases), quiz)
        assertEquals(PracticeReadResult.Captured(PracticeProgressSnapshot(listOf("c1", "c2"), listOf("f1"),
            listOf(PracticeQuizSnapshot("2026-11-13", session)))), result)
        assertEquals(setOf("c1", "c2"), cards)
        assertEquals(setOf("f1"), cases)
        assertEquals(mapOf("2026-11-13" to raw), quiz)
        // Later mutations cannot alter the captured set copy.
        cards.add("later")
        assertEquals(listOf("c1", "c2"), (result as PracticeReadResult.Captured).snapshot.learnedCardIds)
    }

    @Test fun `empty preferences produce an empty unverified capture`() {
        assertEquals(PracticeReadResult.Captured(PracticeProgressSnapshot(emptyList(), emptyList(), emptyList())), read())
    }

    @Test fun `unknown keys unexpected types and invalid quiz data reject everything`() {
        val validCards = mapOf("learned_card_ids" to setOf("card"))
        val badCards = listOf(mapOf("extra" to "value"), mapOf("learned_card_ids" to "not a set"),
            mapOf("learned_card_ids" to setOf("ok", 3)), mapOf("learned_card_ids" to setOf(" ")))
        badCards.forEach { assertEquals(PracticeReadResult.InvalidData, read(cards = it)) }
        assertEquals(PracticeReadResult.InvalidData, read(cases = mapOf("completed_case_ids" to null)))
        val badQuizzes = listOf(
            mapOf("2026-02-30" to raw), mapOf("not-a-date" to raw), mapOf("2026-11-13" to 1),
            mapOf("2026-11-13" to "not json"),
            mapOf("2026-11-13" to raw.replace("\"fingerprint\":", "\"fingerprint\":\"bad\",\"fingerprint\":")),
            mapOf("2026-11-13" to raw.dropLast(1) + ",\"extra\":true}"),
            mapOf("2026-11-13" to " ".repeat(65_537)),
            mapOf("2026-11-13" to Json.encodeToString(session.copy(answers = listOf(4))))
        )
        badQuizzes.forEach { assertEquals(PracticeReadResult.InvalidData, read(cards = validCards, quizzes = it)) }
    }

    @Test fun `preference access failure is not interpreted as empty`() {
        var calls = 0
        val broken = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ ->
            calls++
            throw SecurityException("synthetic failure")
        } as SharedPreferences
        assertEquals(PracticeReadResult.Unavailable, PracticeProgressReader.read(broken, broken, broken))
        assertEquals(1, calls)
    }

    @Test fun `rejects data outside experimental limits without truncating`() {
        val tooManyCards = (0..PracticeProgressFormat.MAX_IDS).map { "c$it" }.toSet()
        assertEquals(PracticeReadResult.InvalidData, read(cards = mapOf("learned_card_ids" to tooManyCards)))
        val tooManyDays = (0..PracticeProgressFormat.MAX_DAYS).associate { it.toString() to raw }
        assertEquals(PracticeReadResult.InvalidData, read(quizzes = tooManyDays))
    }
}
