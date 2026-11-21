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
