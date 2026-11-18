package de.pflegeexamen.app.data.progress

import org.junit.Assert.*
import org.junit.Test

class CaseProgressStoreTest {
    private class MemoryPersistence : CaseProgressPersistence {
        var saved: Set<String> = emptySet()
        override fun loadCompleted(): Set<String> = saved.toSet()
        override fun saveCompleted(ids: Set<String>) { saved = ids.toSet() }
    }

    @Test fun `mark and unmark individual cases and restore across store recreation`() {
        val persistence = MemoryPersistence()
        val store = CaseProgressStore(persistence)
        assertTrue(store.completed.value.isEmpty())
        store.setCompleted("case-day-1-01", true)
        store.setCompleted("case-day-2-01", true)
        store.setCompleted("case-day-1-01", true)
        assertEquals(setOf("case-day-1-01", "case-day-2-01"), CaseProgressStore(persistence).completed.value)
        val restored = CaseProgressStore(persistence)
        restored.setCompleted("case-day-1-01", false)
        assertEquals(setOf("case-day-2-01"), CaseProgressStore(persistence).completed.value)
        restored.setCompleted("case-day-2-01", false)
        assertTrue(CaseProgressStore(persistence).completed.value.isEmpty())
    }

    @Test fun `blank ids cannot be stored`() {
        val store = CaseProgressStore(MemoryPersistence())
        assertThrows(IllegalArgumentException::class.java) { store.setCompleted(" ", true) }
    }
}
