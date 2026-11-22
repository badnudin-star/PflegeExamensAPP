package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CardVisibilityTest {
    private val cards = listOf("a", "b", "c").map { PublishedCard(it, "Thema", "Frage", "Antwort", listOf("Quelle")) }

    @Test fun `hides only learned ids and can reveal or restore them`() {
        val learned = setOf("b", "unknown")
        assertEquals(listOf("a", "c"), CardVisibility.visible(cards, learned, false).map { it.id })
        assertEquals(cards, CardVisibility.visible(cards, learned, true))
        assertEquals(cards, CardVisibility.visible(cards, learned - "b", false))
        assertTrue(CardVisibility.visible(cards, setOf("a", "b", "c"), false).isEmpty())
        assertEquals(3, cards.size) // no content is deleted
    }

    @Test fun `search only learned cards by topic question or answer without changing saved ids`() {
        val deck = listOf(
            PublishedCard("1", "Herz", "Puls prüfen", "Am Handgelenk", listOf("Quelle")),
            PublishedCard("2", "Lunge", "Atmung prüfen", "Atemfrequenz", listOf("Quelle")),
            PublishedCard("3", "Herz", "Blutdruck", "Manschette", listOf("Quelle"))
        )
        val learned = setOf("1", "2", "unknown")
        assertEquals(listOf("1", "2"), CardVisibility.learnedMatching(deck, learned, " ").map { it.id })
        assertEquals(listOf("1"), CardVisibility.learnedMatching(deck, learned, " HERZ ").map { it.id })
        assertEquals(listOf("2"), CardVisibility.learnedMatching(deck, learned, "AtMunG").map { it.id })
        assertEquals(listOf("1"), CardVisibility.learnedMatching(deck, learned, "handgelenk").map { it.id })
        assertTrue(CardVisibility.learnedMatching(deck, learned, "Manschette").isEmpty())
        assertTrue(CardVisibility.learnedMatching(deck, emptySet(), "").isEmpty())
        assertEquals(setOf("1", "2", "unknown"), learned)
        assertEquals(3, deck.size)
        assertEquals(listOf("2"), CardVisibility.learnedMatching(deck, learned - "1", "").map { it.id })
    }

    @Test fun `shuffle remains within deck and learned filtering preserves its order`() {
        val deck = (1..20).map { PublishedCard("$it", "Thema", "Frage $it", "Antwort", listOf("Quelle")) }
        val mixed = CardVisibility.order(deck, shuffled = true, random = Random(42))
        assertEquals(deck.map { it.id }.toSet(), mixed.map { it.id }.toSet())
        assertEquals(deck.size, mixed.size)
        assertNotEquals(deck, mixed)
        val learned = setOf("2", "4", "6")
        assertEquals(mixed.filterNot { it.id in learned }, CardVisibility.visible(mixed, learned, false))
        assertEquals(mixed, CardVisibility.visible(mixed, learned, true))
        assertEquals(deck, CardVisibility.order(deck, shuffled = false))
        assertEquals((1..20).map { "$it" }, deck.map { it.id })
        assertEquals(emptyList<PublishedCard>(), CardVisibility.order(emptyList(), shuffled = true))
    }

    @Test fun `continue at next card or previous when last disappears`() {
        assertEquals(0, CardVisibility.indexAfterRemoval(0, 3))
        assertEquals(1, CardVisibility.indexAfterRemoval(1, 3))
        assertEquals(1, CardVisibility.indexAfterRemoval(2, 3))
        assertEquals(0, CardVisibility.indexAfterRemoval(0, 1))
    }
}
