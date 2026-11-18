package de.pflegeexamen.app.domain.cards

import de.pflegeexamen.app.domain.content.PublishedCard

object CardVisibility {
    fun visible(cards: List<PublishedCard>, learned: Set<String>, showLearned: Boolean): List<PublishedCard> =
        if (showLearned) cards else cards.filterNot { it.id in learned }

    /** Keep the same position if a following card exists; otherwise show the previous card. */
    fun indexAfterRemoval(index: Int, previousSize: Int): Int = index.coerceAtMost((previousSize - 2).coerceAtLeast(0))
}
