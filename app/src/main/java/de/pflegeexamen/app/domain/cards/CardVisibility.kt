package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard
import kotlin.random.Random

object CardVisibility {
    /** Arrange only the selected deck; visibility is applied separately and never changes the order. */
    fun order(cards: List<PublishedCard>, shuffled: Boolean, random: Random = Random.Default): List<PublishedCard> =
        if (shuffled) cards.shuffled(random) else cards.toList()

    fun visible(cards: List<PublishedCard>, learned: Set<String>, showLearned: Boolean): List<PublishedCard> =
        if (showLearned) cards else cards.filterNot { it.id in learned }

    /** Keep the same position if a following card exists; otherwise show the previous card. */
    fun indexAfterRemoval(index: Int, previousSize: Int): Int = index.coerceAtMost((previousSize - 2).coerceAtLeast(0))
}
