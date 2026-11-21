package de.pflegeexamen.app.domain.cases

import de.pflegeexamen.app.domain.model.DefaultCatalogs
import org.junit.Assert.*
import org.junit.Test

class CasePresentationTest {
    @Test fun `day topics refer only to existing fixed topic indices for the given day`() {
        val topics = DefaultCatalogs.defaultPreparations
        for (day in 1..3) {
            val indices = CasePresentation.topicIndicesForDay(day)
            assertTrue(indices.isNotEmpty())
            assertEquals(indices.distinct(), indices)
            assertEquals(topics.indices.filter { topics[it].examDayTag == "Tag $day" }, indices)
            assertTrue(indices.all { it in topics.indices && topics[it].examDayTag == "Tag $day" })
        }
        assertTrue(CasePresentation.topicIndicesForDay(null).isEmpty())
        assertTrue(CasePresentation.topicIndicesForDay(0).isEmpty())
        assertTrue(CasePresentation.topicIndicesForDay(4).isEmpty())
    }

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
