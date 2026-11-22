package de.pflegeexamen.app.domain.notes

import de.pflegeexamen.app.domain.content.PublishedNote
import org.junit.Assert.*
import org.junit.Test

class NoteSearchIndexTest {
    private fun note(topic: String, main: String, simple: String, version: Int = 2) =
        PublishedNote(topic, simple, "nicht angezeigt", main, listOf("interne Quelle"), version)

    @Test fun `searches displayed versions independently with the same rules as the jump`() {
        val main = """
            EXAMENSVORBEREITUNG · LERNZETTEL
            1
            GRUNDLAGEN
            Der erste Absatz enthält Nadelwort.
            QUELLEN UND GRENZEN
            VersteckterQuellenbegriff
        """.trimIndent()
        val simple = "Einfaches Stichwort im Text."
        val index = NoteSearchIndex(listOf(note("Thema", main, simple), note("Alt", "Nadelwort", "Nadelwort", 1)))
        assertEquals(NoteSearchHit(true, false), index.search("  NADELWORT ")["Thema"])
        assertEquals(NoteSearchHit(false, true), index.search("stichwort")["Thema"])
        assertFalse(index.search("Nadelwort").containsKey("Alt"))
        assertTrue(index.search("VersteckterQuellenbegriff").isEmpty())
        assertTrue(index.search("interne Quelle").isEmpty())
        assertTrue(index.search("nicht angezeigt").isEmpty())
        assertTrue(index.search("  ").isEmpty())
        assertTrue(index.search("fehlt").isEmpty())
        assertNotNull(NotePresentation.firstMatch(NotePresentation.blocks(main), "  NADELWORT "))
        assertNull(NotePresentation.firstMatch(NotePresentation.blocks(simple), "  NADELWORT "))
    }

    @Test fun `both versions can match and a new index reflects updated notes`() {
        val old = note("Thema", "Wort in Langversion", "Wort in Kurzversion")
        val initial = NoteSearchIndex(listOf(old))
        assertEquals(NoteSearchHit(true, true), initial.search("Wort")["Thema"])
        val updated = NoteSearchIndex(listOf(old.copy(detailed = "Neuer Text", short = "Neuer Text")))
        assertTrue(updated.search("Wort").isEmpty())
        assertEquals(NoteSearchHit(true, true), updated.search("Neuer")["Thema"])
        assertEquals(NoteSearchHit(true, true), initial.search("Wort")["Thema"])
    }
}
