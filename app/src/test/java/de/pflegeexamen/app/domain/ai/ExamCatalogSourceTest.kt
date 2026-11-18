package de.pflegeexamen.app.domain.ai

import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import org.junit.Assert.*
import org.junit.Test

class ExamCatalogSourceTest {
    @Test fun `operator list matches the photographed variants`() {
        val expected = setOf(
            "Nennen", "Beschreiben (statisch)", "Beschreiben (dynamisch / Vorgang)",
            "Definieren", "Erklären", "Begründen", "Erläutern", "Erläutern (mit Fallbezug)",
            "Analysieren", "Analysieren (im Fließtext)", "Stellung nehmen", "Diskutieren (Erörtern)",
            "Entwickeln Sie (z. B. Hypothese)", "Formulieren Sie (Pflegeplan / Ablaufplan)",
            "Formulieren Sie (eine Pflegemaßnahme)", "Beurteilen Sie...", "Beschriften Sie"
        )
        assertEquals(expected, DefaultCatalogs.defaultOperators.map { it.name }.toSet())
        assertTrue(DefaultCatalogs.isAllowedOperator(" nennen "))
        assertFalse(DefaultCatalogs.isAllowedOperator("Erfinden"))
        assertEquals(CaseRelationRule.NO_CASE_RELATION, DefaultCatalogs.defaultOperators.first { it.name == "Definieren" }.caseRelationRule)
        // The photos state that the specific examples are general, not that every
        // explanation or justification can never refer to a case.
        listOf("Erklären", "Begründen", "Erläutern").forEach { name ->
            assertEquals(CaseRelationRule.OPTIONAL_CASE_RELATION, DefaultCatalogs.defaultOperators.first { it.name == name }.caseRelationRule)
        }
    }

    @Test fun `preparation competencies map to the descriptions in the list`() {
        val codes = DefaultCatalogs.defaultPreparations.flatMap { it.competenceFocusCodes.split(",").map(String::trim) }.toSet()
        assertEquals(codes, DefaultCatalogs.competenceDescriptions.keys)
        assertTrue(DefaultCatalogs.descriptionsForCompetences("I.1, IV.1").first().second.contains("verantwortlich planen"))
        assertTrue(DefaultCatalogs.descriptionsForCompetences("I.1, unbekannt").size == 1)
        assertTrue(DefaultCatalogs.defaultPreparations.filter { it.isClinicalPicture }.all { "Anatomie & Physiologie" in it.focusParameters() })
    }
}
