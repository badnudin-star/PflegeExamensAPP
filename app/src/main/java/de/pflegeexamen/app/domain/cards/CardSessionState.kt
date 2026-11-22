package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.random.Random

/** Compact, temporary UI state; learned IDs remain in their separate persistent store. */
@Serializable
data class CardSessionState(
    val deckFingerprint: String,
    val shuffled: Boolean,
    val seed: Long,
    val cardId: String? = null,
    val showingAnswer: Boolean = false
) {
    /** A changed/reordered deck never reuses a stale selection or answer side. */
    fun forDeck(fingerprint: String, defaultShuffled: Boolean): CardSessionState =
        if (deckFingerprint == fingerprint) this else CardSessionState(fingerprint, defaultShuffled, seed)

    fun ordered(cards: List<PublishedCard>): List<PublishedCard> =
        CardVisibility.order(cards, shuffled, Random(seed))

    /** A hidden or missing card cannot transfer its answer side to a different card. */
    fun forVisible(visible: List<PublishedCard>): CardSessionState {
        if (cardId != null && visible.any { it.id == cardId }) return this
        return copy(cardId = visible.firstOrNull()?.id, showingAnswer = false)
    }

    fun select(card: PublishedCard): CardSessionState = copy(cardId = card.id, showingAnswer = false)

    fun afterHide(visible: List<PublishedCard>, index: Int): CardSessionState {
        require(index in visible.indices && visible[index].id == cardId)
        val remaining = visible.filterIndexed { i, _ -> i != index }
        return copy(cardId = remaining.getOrNull(CardVisibility.indexAfterRemoval(index, visible.size))?.id, showingAnswer = false)
    }
}

/** Fingerprint includes order and all card content; never persist the card content in UI state. */
fun cardDeckFingerprint(cards: List<PublishedCard>): String =
    MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(cards).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
