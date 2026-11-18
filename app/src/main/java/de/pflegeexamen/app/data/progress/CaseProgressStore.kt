package de.pflegeexamen.app.data.progress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local progress is keyed by published case ID and is independent of catalog revisions. */
interface CaseProgressPersistence {
    fun loadCompleted(): Set<String>
    fun saveCompleted(ids: Set<String>)
}

class CaseProgressStore(private val persistence: CaseProgressPersistence) {
    private val _completed = MutableStateFlow(persistence.loadCompleted().toSet())
    val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    fun setCompleted(id: String, completed: Boolean) {
        require(id.isNotBlank())
        val updated = if (completed) _completed.value + id else _completed.value - id
        if (updated == _completed.value) return
        persistence.saveCompleted(updated.toSet())
        _completed.value = updated
    }
}
