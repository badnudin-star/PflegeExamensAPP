package de.pflegeexamen.app.domain.cases

import de.pflegeexamen.app.domain.content.PublishedCase
import de.pflegeexamen.app.domain.content.PublishedCaseQuestion
import org.junit.Assert.*
import org.junit.Test

class CaseSearchTest {
    private val first = PublishedCase(
        id = "case-a", published = "2026-11-13", diseases = listOf("Herzschwäche"),
        text = "Die Person erhält\nUnterstützung zu Hause.", sources = listOf("GeheimeQuelle"),
        questions = listOf(PublishedCaseQuestion("Welche Beobachtung?", "Flüssigkeit bilanzieren.", listOf("Quelle"))),
        examDay = 1, title = "Morgens", instructions = "Bearbeiten Sie die Aufgaben.", answerNote = "Hinweis zur Bearbeitung."
    )
    private val second = first.copy(id = "case-b", diseases = listOf("Lunge"), title = "Nacht", examDay = 2,
        text = "Atemnot", questions = listOf(PublishedCaseQuestion("Was ist relevant?", "Atemfrequenz beobachten.", listOf("Quelle"))))
    private val index = CaseSearchIndex(listOf(first, second))

    @Test fun `finds every supported field and groups categories without revealing answers`() {
        assertEquals(listOf(CaseSearchField.TITLE), index.search("HERZSCHWÄCHE").single().fields)
        assertEquals(listOf(CaseSearchField.TITLE), index.search("morgens").single().fields)
        assertEquals(listOf(CaseSearchField.TEXT), index.search("erhält unterstützung").single().fields)
        assertEquals(listOf(CaseSearchField.INSTRUCTIONS), index.search("hinweis zur bearbeitung").map { it.fields }.first())
        assertEquals(listOf(CaseSearchField.QUESTION), index.search("Welche Beobachtung").single().fields)
        assertEquals(listOf(CaseSearchField.ANSWER), index.search("FLÜSSIGKEIT").single().fields)
        assertEquals("case-a", index.search("FLÜSSIGKEIT").single().case.id)
        assertEquals(listOf("case-a", "case-b"), index.search("Aufgaben").map { it.case.id })
        assertTrue(index.search("GeheimeQuelle").isEmpty())
    }

    @Test fun `whitespace and pdf line breaks normalize without modifying source`() {
        assertEquals(listOf("case-a"), index.search("  erhält\n\tUNTERSTÜTZUNG ").map { it.case.id })
        assertEquals(listOf("case-a"), index.search("erhält\u00a0unterstützung").map { it.case.id })
        assertEquals("Die Person erhält\nUnterstützung zu Hause.", first.text)
        assertEquals("Flüssigkeit bilanzieren.", first.questions.single().answer)
    }

    @Test fun `empty and absent query preserve order and different days`() {
        val all = index.search(" \n ")
        assertEquals(listOf(first, second), all.map { it.case })
        assertTrue(all.all { it.fields.isEmpty() })
        assertEquals(listOf(1,2), all.map { it.case.examDay })
        assertTrue(index.search("nicht vorhanden").isEmpty())
        assertTrue(CaseSearchIndex(emptyList()).search("").isEmpty())
    }

    @Test fun `returns each matching field once even when several questions match`() {
        val matching = first.copy(text="Puls", questions=listOf(
            PublishedCaseQuestion("Puls messen?", "Puls kontrollieren.", listOf("Quelle")),
            PublishedCaseQuestion("Puls wie oft?", "Puls dokumentieren.", listOf("Quelle"))
        ))
        assertEquals(listOf(CaseSearchField.TEXT, CaseSearchField.QUESTION, CaseSearchField.ANSWER),
            CaseSearchIndex(listOf(matching)).search("puls").single().fields)
    }
}
