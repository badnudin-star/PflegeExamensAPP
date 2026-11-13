package de.pflegeexamen.app.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanTextAnalyzerTest {

    @Test
    fun `cleanRawText removes hyphenated linebreaks from PDF and OCR`() {
        val raw = "Die Dekubitus-\nprophylaxe erfordert regel-\nmäßige Beobachtung."
        val cleaned = GermanTextAnalyzer.cleanRawText(raw)
        assertEquals("Die Dekubitusprophylaxe erfordert regelmäßige Beobachtung.", cleaned)
    }

    @Test
    fun `splitSentences preserves German abbreviations and decimal numbers`() {
        val text = "Bei Patienten mit z. B. Immobilität oder ca. 38,5 °C Fieber steigt das Risiko. " +
            "Deshalb erfolgt u. a. eine Inspektion der Haut. 1. Schritt ist das Assessment."
        val sentences = GermanTextAnalyzer.splitSentences(text)
        assertEquals(3, sentences.size)
        assertTrue(sentences[0].contains("z. B."))
        assertTrue(sentences[0].contains("38,5"))
        assertTrue(sentences[1].contains("u. a."))
    }

    @Test
    fun `matchesTerm recognizes German compound nouns and umlaut normalization`() {
        val text = "Eine konsequente Dekubitusprophylaxe durch Gefäßentlastung und Lagerungswechsel."
        val tokens = GermanTextAnalyzer.tokenizeKeywords(text).toSet()
        val normText = GermanTextAnalyzer.normalizeForMatching(text)

        assertTrue(GermanTextAnalyzer.matchesTerm("Dekubitus", tokens, normText))
        assertTrue(GermanTextAnalyzer.matchesTerm("Gefaess", tokens, normText))
        assertTrue(GermanTextAnalyzer.matchesTerm("Lagerung", tokens, normText))
        assertFalse(GermanTextAnalyzer.matchesTerm("Diabetes", tokens, normText))
    }
}
