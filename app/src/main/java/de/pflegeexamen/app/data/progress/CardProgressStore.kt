package de.pflegeexamen.app.data.progress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CardProgressPersistence {
    fun loadLearned(): Set<String>
    fun saveLearned(ids: Set<String>)
}

/** Only the IDs are stored locally; the published cards are never modified. */
class CardProgressStore(private val persistence: CardProgressPersistence) {
    private val _learned = MutableStateFlow(persistence.loadLearned().toSet())
    val learned: StateFlow<Set<String>> = _learned.asStateFlow()

    fun setLearned(id: String, learned: Boolean) {
        require(id.isNotBlank())
        val updated = if (learned) _learned.value + id else _learned.value - id
        if (updated == _learned.value) return
        persistence.saveLearned(updated.toSet())
        _learned.value = updated
    }
}
