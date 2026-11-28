package de.pflegeexamen.app.domain.content

enum class RefreshDecision { UPDATE, UP_TO_DATE, SERVER_OLDER, REJECTED }

/**
 * A higher revision alone does not prove a snapshot is complete. Preserve the
 * installed notes, cards and cases by stable key; quizzes may be archived.
 * This guards against omissions, not changes to text or clinical errors.
 */
object CatalogUpdatePolicy {
    private fun preservesLearningItems(base: LearningCatalog, candidate: LearningCatalog): Boolean {
        val topics = candidate.notes.map { it.topic }.toSet()
        val cards = candidate.cards.map { it.id }.toSet()
        val retired = candidate.retiredCardIds.toSet()
        val cases = candidate.cases.map { it.id }.toSet()
        val retiredCases = candidate.retiredCaseIds.toSet()
        // Explicit, cumulative retirement is different from accidental omission.
        // Retired IDs may never become active again, avoiding stale learned marks.
        return retired.size == candidate.retiredCardIds.size && retired.none { it.isBlank() || it in cards } &&
            base.retiredCardIds.all { it in retired } &&
            retiredCases.size == candidate.retiredCaseIds.size && retiredCases.none { it.isBlank() || it in cases } &&
            base.retiredCaseIds.all { it in retiredCases } &&
            base.notes.all { it.topic in topics } &&
            base.cards.all { it.id in cards || it.id in retired } &&
            base.cases.all { it.id in cases || it.id in retiredCases }
    }

    /** Equal revisions must describe the same parsed catalog, not competing snapshots. */
    fun canUseAtStartup(base: LearningCatalog, candidate: LearningCatalog): Boolean =
        when {
            candidate.revision < base.revision -> false
            candidate.revision == base.revision -> candidate == base
            else -> preservesLearningItems(base, candidate)
        }

    fun decideRefresh(base: LearningCatalog, candidate: LearningCatalog): RefreshDecision = when {
        candidate.revision < base.revision -> RefreshDecision.SERVER_OLDER
        candidate.revision == base.revision && candidate == base -> RefreshDecision.UP_TO_DATE
        candidate.revision == base.revision -> RefreshDecision.REJECTED
        preservesLearningItems(base, candidate) -> RefreshDecision.UPDATE
        else -> RefreshDecision.REJECTED
    }

    fun canRefresh(base: LearningCatalog, candidate: LearningCatalog): Boolean =
        decideRefresh(base, candidate) == RefreshDecision.UPDATE
}
