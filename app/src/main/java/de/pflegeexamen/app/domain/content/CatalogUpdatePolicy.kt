package de.pflegeexamen.app.domain.content

/**
 * A higher revision alone does not prove a snapshot is complete. Preserve the
 * installed notes, cards and cases by stable key; quizzes may be archived.
 * This guards against omissions, not changes to text or clinical errors.
 */
object CatalogUpdatePolicy {
    private fun preservesLearningItems(base: LearningCatalog, candidate: LearningCatalog): Boolean {
        val topics = candidate.notes.map { it.topic }.toSet()
        val cards = candidate.cards.map { it.id }.toSet()
        val cases = candidate.cases.map { it.id }.toSet()
        return base.notes.all { it.topic in topics } &&
            base.cards.all { it.id in cards } && base.cases.all { it.id in cases }
    }

    /** Equal revisions must describe the same parsed catalog, not competing snapshots. */
    fun canUseAtStartup(base: LearningCatalog, candidate: LearningCatalog): Boolean =
        when {
            candidate.revision < base.revision -> false
            candidate.revision == base.revision -> candidate == base
            else -> preservesLearningItems(base, candidate)
        }

    fun canRefresh(base: LearningCatalog, candidate: LearningCatalog): Boolean =
        candidate.revision > base.revision && preservesLearningItems(base, candidate)
}
