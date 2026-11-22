package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import kotlin.random.Random

data class TopicCardCounts(val total: Int, val visible: Int, val learned: Int)

object CardVisibility {
    /** Arrange only the selected deck; visibility is applied separately and never changes the order. */
    fun order(cards: List<PublishedCard>, shuffled: Boolean, random: Random = Random.Default): List<PublishedCard> =
        if (shuffled) cards.shuffled(random) else cards.toList()

    fun visible(cards: List<PublishedCard>, learned: Set<String>, showLearned: Boolean): List<PublishedCard> =
        if (showLearned) cards else cards.filterNot { it.id in learned }

    /** Only exact topic matches count; unknown learned IDs never affect the totals. */
    fun topicCounts(cards: List<PublishedCard>, topic: String, learned: Set<String>, showLearned: Boolean): TopicCardCounts {
        val matching = cards.filter { it.topic == topic }
        val learnedCount = matching.count { it.id in learned }
        return TopicCardCounts(matching.size, if (showLearned) matching.size else matching.size - learnedCount, learnedCount)
    }

    /** Search learned cards only; card texts and saved IDs are not changed. */
    fun learnedMatching(cards: List<PublishedCard>, learned: Set<String>, query: String): List<PublishedCard> {
        val term = query.trim()
        return cards.filter { card ->
            card.id in learned && (term.isEmpty() || card.topic.contains(term, ignoreCase = true) ||
                card.front.contains(term, ignoreCase = true) || card.back.contains(term, ignoreCase = true))
        }
    }

    /** Keep the same position if a following card exists; otherwise show the previous card. */
    fun indexAfterRemoval(index: Int, previousSize: Int): Int = index.coerceAtMost((previousSize - 2).coerceAtLeast(0))
}
