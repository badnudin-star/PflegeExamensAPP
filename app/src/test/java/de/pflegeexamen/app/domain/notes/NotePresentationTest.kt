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
    @Test fun `full text mode preserves every character including sources tables and empty lines`() {
        val raw = "EXAMENSVORBEREITUNG · LERNZETTEL\n1\n" +
            (1..65).joinToString("\n") { "Zeile $it   Spalte\tInhalt" } + "\n\nQUELLEN UND GRENZEN\nVerweis\n"
        val blocks = NotePresentation.fullTextBlocks(raw)
        assertEquals(raw, blocks.joinToString("\n") { it.text })
        assertTrue(blocks.size > 1)
        assertTrue(blocks.none { it.isHeading })
        assertTrue(NotePresentation.fullTextBlocks("").isEmpty())
    }

    @Test fun `all bundled variants can be reconstructed from full text blocks`() {
        val catalog = requireNotNull(de.pflegeexamen.app.domain.content.LearningCatalogCodec.parse(
            java.io.File("src/main/assets/learning_content.json").readText()))
        for (note in catalog.notes) for (raw in listOf(note.short, note.detailed)) {
            assertEquals(note.topic, raw, NotePresentation.fullTextBlocks(raw).joinToString("\n") { it.text })
        }
    }

    @Test fun `outline uses display block indices and preserves repeated headings`() {
        val blocks = listOf(
            NoteBlock("Einleitung"), NoteBlock("Grundlagen", true), NoteBlock("Text"),
            NoteBlock("Grundlagen", true), NoteBlock("  ", true), NoteBlock("Weiterer Text"), NoteBlock("Maßnahmen", true)
        )
        assertEquals(listOf(NoteHeading(1, "Grundlagen"), NoteHeading(3, "Grundlagen"), NoteHeading(6, "Maßnahmen")),
            NotePresentation.headings(blocks))
        assertEquals("Grundlagen", blocks[NotePresentation.headings(blocks)[1].blockIndex].text)
        assertTrue(NotePresentation.headings(listOf(NoteBlock("Nur Text"))).isEmpty())
        assertTrue(NotePresentation.headings(emptyList()).isEmpty())
    }

    @Test fun `outline uses rendered headings without changing or adding source text`() {
        val raw = """
            EXAMENSVORBEREITUNG · LERNZETTEL
            1
            GRUNDLAGEN
            Text bleibt sichtbar.
            MASSNAHMEN
            Weiterer Text.
            QUELLEN UND GRENZEN
            Interne Referenzen nicht anzeigen.
        """.trimIndent()
        val before = raw.toString()
        val blocks = NotePresentation.blocks(raw)
        val headings = NotePresentation.headings(blocks)
        assertEquals(listOf("GRUNDLAGEN", "MASSNAHMEN"), headings.map { it.text })
        assertTrue(headings.all { blocks[it.blockIndex].isHeading && blocks[it.blockIndex].text == it.text })
        assertFalse(headings.toString().contains("QUELLEN"))
        assertEquals(before, raw)
    }

    @Test fun `match navigation follows nonoverlapping highlighted positions across blocks`() {
        val blocks = listOf(NoteBlock(""), NoteBlock("Ababa ABABa"), NoteBlock("ohne"), NoteBlock("aba", true))
        val first = NotePresentation.nextMatch(blocks, "  ABA  ")!!
        val second = NotePresentation.nextMatch(blocks, "aba", first)!!
        val third = NotePresentation.nextMatch(blocks, "aba", second)!!
        assertEquals(NoteMatch(1, 0), first)
        assertEquals(NoteMatch(1, 6), second)
        assertEquals(NoteMatch(3, 0), third)
        assertNull(NotePresentation.previousMatch(blocks, "aba", first))
        assertEquals(first, NotePresentation.previousMatch(blocks, "aba", second))
        assertEquals(second, NotePresentation.previousMatch(blocks, "aba", third))
        assertNull(NotePresentation.nextMatch(blocks, "aba", third))
        assertFalse(NotePresentation.isMatch(blocks, "aba", NoteMatch(1, 2))) // overlaps
        assertNull(NotePresentation.nextMatch(blocks, "aba", NoteMatch(99, 0)))
        assertNull(NotePresentation.previousMatch(blocks, "aba", NoteMatch(1, -1)))
        assertNull(NotePresentation.nextMatch(blocks, "  "))
        assertNull(NotePresentation.nextMatch(emptyList(), "aba"))
        assertNull(NotePresentation.nextMatch(blocks, "nicht gefunden"))
        assertEquals(NotePresentation.firstMatch(blocks, "aba"), first.blockIndex)
    }

    @Test fun `match offsets use displayed UTF16 and leave source untouched`() {
        val raw = "Erster Absatz mit 😀 Nadelwort.\n\nZweiter Absatz mit NADELWORT."
        val blocks = NotePresentation.blocks(raw)
        val first = NotePresentation.nextMatch(blocks, "nadelwort")!!
        val next = NotePresentation.nextMatch(blocks, "nadelwort", first)!!
        assertEquals(blocks[first.blockIndex].text.indexOf("Nadelwort"), first.offset)
        assertEquals(NoteMatch(1, blocks[1].text.indexOf("NADELWORT")), next)
        assertEquals(first, NotePresentation.previousMatch(blocks, "nadelwort", next))
        assertEquals("Erster Absatz mit 😀 Nadelwort.\n\nZweiter Absatz mit NADELWORT.", raw)
    }

    @Test fun `new PDFs present headings and hide end references`() {
        val text = """
            EXAMENSVORBEREITUNG · LERNZETTEL
            1
            EINFACH: HERZINSUFFIZIENZ
            WIE ARBEITET DAS HERZ?
            Das Herz pumpt Blut.
            EXAMENSVORBEREITUNG · LERNZETTEL 2
            WANN IST ES EIN NOTFALL?
            Atemnot kann auftreten.
            QUELLEN UND GRENZEN
            Interne Referenzen nicht anzeigen.
        """.trimIndent()
        val blocks = NotePresentation.blocks(text)
        assertTrue(blocks.any { it.isHeading && it.text == "WANN IST ES EIN NOTFALL?" })
        assertTrue(blocks.any { it.text.contains("Atemnot") })
        assertFalse(blocks.toString().contains("Interne Referenzen"))
        assertEquals(blocks.indexOfFirst { it.text.contains("Atemnot") }, NotePresentation.firstMatch(blocks, "atemnot"))
        assertNull(NotePresentation.firstMatch(blocks, "  "))
        assertNull(NotePresentation.firstMatch(blocks, "fehlt"))
    }
}
