package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CardSessionStateTest {
    private val cards = (1..20).map { PublishedCard("card-$it", "Thema", "Frage $it", "Antwort $it", listOf("Quelle")) }
    private val fingerprint = cardDeckFingerprint(cards)

    @Test fun `restored state retains seeded order selected card and side`() {
        val initial = CardSessionState(fingerprint, shuffled = true, seed = 42)
        val order = initial.ordered(cards)
        assertNotEquals(cards, order)
        val active = initial.select(order[7]).copy(showingAnswer = true)
        val saved = Json.encodeToString(active)
        assertFalse(saved.contains("Antwort 8")); assertFalse(saved.contains("Frage 8"))
        val restored = Json.decodeFromString<CardSessionState>(saved).forDeck(cardDeckFingerprint(cards), false)
        assertEquals(active, restored.forVisible(restored.ordered(cards)))
        assertEquals(order.map { it.id }, restored.ordered(cards).map { it.id })
        assertEquals(order[7].id, restored.cardId)
        assertTrue(restored.showingAnswer)
    }

    @Test fun `learned filtering leaves order and selected card unchanged when still visible`() {
        val state = CardSessionState(fingerprint, true, 17).select(cards[8]).copy(showingAnswer = true)
        val order = state.ordered(cards)
        val visible = CardVisibility.visible(order, setOf(cards[1].id, cards[2].id), false)
        assertEquals(order.filterNot { it.id in setOf(cards[1].id, cards[2].id) }, visible)
        assertEquals(state, state.forVisible(visible))
        assertEquals(order, state.ordered(cards))
    }

    @Test fun `changed content or order resets selection and side`() {
        val state = CardSessionState(fingerprint, true, 42, cards[5].id, true)
        val changed = cards.toMutableList().apply { this[5] = this[5].copy(back = "Korrektur") }
        assertNotEquals(fingerprint, cardDeckFingerprint(changed))
        assertNotEquals(fingerprint, cardDeckFingerprint(cards.reversed()))
        val reset = state.forDeck(cardDeckFingerprint(changed), defaultShuffled = false)
        assertFalse(reset.shuffled); assertNull(reset.cardId); assertFalse(reset.showingAnswer)
        assertEquals(changed.first().id, reset.forVisible(reset.ordered(changed)).cardId)
        assertEquals(state, state.forDeck(fingerprint, defaultShuffled = false))
    }

    @Test fun `missing or hidden card chooses safe first card without revealing answer`() {
        val state = CardSessionState(fingerprint, false, 99, cards[3].id, true)
        val visible = cards.filterNot { it.id == cards[3].id }
        val restored = state.forVisible(visible)
        assertEquals(cards[0].id, restored.cardId); assertFalse(restored.showingAnswer)
        val empty = state.forVisible(emptyList())
        assertNull(empty.cardId); assertFalse(empty.showingAnswer)
        assertEquals(cards[0].id, empty.forVisible(cards).cardId)
    }

    @Test fun `hiding first middle last or sole card chooses next or previous`() {
        val deck = cards.take(3)
        val state = CardSessionState(cardDeckFingerprint(deck), false, 1)
        assertEquals(deck[1].id, state.select(deck[0]).afterHide(deck, 0).cardId)
        val next = state.select(deck[1]).copy(showingAnswer = true).afterHide(deck, 1)
        assertEquals(deck[2].id, next.cardId); assertFalse(next.showingAnswer)
        assertEquals(deck[1].id, state.select(deck[2]).afterHide(deck, 2).cardId)
        assertNull(state.select(deck[0]).afterHide(deck.take(1), 0).cardId)
        assertThrows(IllegalArgumentException::class.java) { state.select(deck[1]).afterHide(deck, 0) }
    }
}
