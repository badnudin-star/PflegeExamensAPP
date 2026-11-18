package de.pflegeexamen.app.domain.cases

import org.junit.Assert.*
import org.junit.Test

class CasePresentationTest {
    @Test fun `case sections keep intro and prose and separate headings`() {
        val text = "Fiktive Übung.\n\nFALLSITUATION\nZu Hause\nDie Person wohnt in einer\nWohnung und ist wach.\nKrankenhausaufenthalt\nDie Person erhält Unterstützung."
        val sections = CasePresentation.sections(text)
        assertEquals("Fiktive Übung.", sections[0].paragraphs.single())
        assertEquals("FALLSITUATION", sections[1].heading)
        assertTrue(sections[1].paragraphs.joinToString().contains("Die Person wohnt in einer Wohnung und ist wach."))
        assertEquals("Krankenhausaufenthalt", sections[2].heading)
        assertEquals("Die Person erhält Unterstützung.", sections[2].paragraphs.single())
    }
}
