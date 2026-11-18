package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun `continue at next card or previous when last disappears`() {
        assertEquals(0, CardVisibility.indexAfterRemoval(0, 3))
        assertEquals(1, CardVisibility.indexAfterRemoval(1, 3))
        assertEquals(1, CardVisibility.indexAfterRemoval(2, 3))
        assertEquals(0, CardVisibility.indexAfterRemoval(0, 1))
    }
}
