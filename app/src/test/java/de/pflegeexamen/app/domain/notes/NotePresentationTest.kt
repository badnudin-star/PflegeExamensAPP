package de.pflegeexamen.app.domain.notes

import org.junit.Assert.*
import org.junit.Test

class NotePresentationTest {
    @Test fun `formats PDF pages and retains important prose and inline citations`() {
        val text = """
            EXAMEN 2027 | LERNZETTEL
            1
            Herzinsuffizienz
            Ausführlich · Lernzettel zum Examen Frühjahr 2027
            Definition [Pflege 1–2]
            Erste Zeile mit
            Fortsetzung.
            • Erster Punkt
            wird fortgesetzt.
            EXAMEN 2027 | LERNZETTEL
            2
            • Zweiter Punkt.
            Ein längerer Titel [Pflege PDF-S.
            12–14]
            Wichtig: sofort Hilfe holen.
            Quellen und Einordnung
            Fundstellen bezeichnen PDF-Seiten der Originaldateien. Nicht anzeigen.
        """.trimIndent()
        val blocks = NotePresentation.sections(text)
        assertEquals(2, blocks.size)
        assertEquals("Definition [Pflege 1–2]", blocks[0].heading)
        assertEquals(listOf("Erste Zeile mit Fortsetzung.", "• Erster Punkt wird fortgesetzt.", "• Zweiter Punkt."), blocks[0].paragraphs)
        assertEquals("Ein längerer Titel [Pflege PDF-S. 12–14]", blocks[1].heading)
        assertEquals("Wichtig: sofort Hilfe holen.", blocks[1].paragraphs.single())
        assertFalse(blocks.toString().contains("Fundstellen bezeichnen"))
    }
    @Test fun `unknown formats preserve content and do not strip source-like sections`() {
        val text = "Beachte die Einordnung.\nQuellen und Einordnung\nEigener Text ohne bekannte Fußzeile."
        assertTrue(NotePresentation.sections(text).flatMap { it.paragraphs }.joinToString().contains("Quellen und Einordnung"))
        assertTrue(NotePresentation.sections("").isEmpty())
    }
}
