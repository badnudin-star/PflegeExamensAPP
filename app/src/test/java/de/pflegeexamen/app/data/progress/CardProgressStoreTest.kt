package de.pflegeexamen.app.data.progress

import org.junit.Assert.*
import org.junit.Test

class CardProgressStoreTest {
    private class MemoryPersistence : CardProgressPersistence {
        var saved: Set<String> = emptySet()
        override fun loadLearned(): Set<String> = saved.toSet()
        override fun saveLearned(ids: Set<String>) { saved = ids.toSet() }
    }

    @Test fun `mark restore and unmark cards across store recreation`() {
        val persistence = MemoryPersistence()
        val store = CardProgressStore(persistence)
        store.setLearned("card-01-001", true)
        store.setLearned("card-01-002", true)
        store.setLearned("card-01-001", true)
        val restored = CardProgressStore(persistence)
        assertEquals(setOf("card-01-001", "card-01-002"), restored.learned.value)
        restored.setLearned("card-01-001", false)
        assertEquals(setOf("card-01-002"), CardProgressStore(persistence).learned.value)
    }

    @Test fun `blank id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CardProgressStore(MemoryPersistence()).setLearned(" ", true) }
    }
}
