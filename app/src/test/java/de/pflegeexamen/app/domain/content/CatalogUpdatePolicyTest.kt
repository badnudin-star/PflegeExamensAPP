package de.pflegeexamen.app.domain.content

import org.junit.Assert.*
import org.junit.Test

class CatalogUpdatePolicyTest {
    private val source = listOf("Quelle")
    private val base = LearningCatalog(
        revision = 5,
        notes = listOf(PublishedNote("Thema", "kurz", "mittel", "lang", source)),
        cards = listOf(PublishedCard("a", "Thema", "Frage", "Antwort", source)),
        cases = listOf(PublishedCase("fall", "2026-01-01", listOf("Fall"), "Text", source,
            listOf(PublishedCaseQuestion("Frage", "Antwort", source)))),
        quizzes = listOf(PublishedQuiz("q", "2026-01-01", "Thema", "Frage", listOf("a", "b", "c", "d"), 0, "Erklärung", source))
    )

    @Test fun `newer complete snapshots can add items or archive quizzes`() {
        val next = base.copy(revision = 6, quizzes = emptyList(), cards = base.cards + base.cards[0].copy(id = "b"))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, next))
        assertTrue(CatalogUpdatePolicy.canUseAtStartup(base, next))
        assertTrue(CatalogUpdatePolicy.canRefresh(LearningCatalog(), base))
    }

    @Test fun `a higher revision cannot silently omit installed learning items`() {
        for (incomplete in listOf(base.copy(notes = emptyList()), base.copy(cards = emptyList()), base.copy(cases = emptyList()),
                                  base.copy(notes = listOf(base.notes[0].copy(topic = "Anderes Thema"))),
                                  base.copy(cards = listOf(base.cards[0].copy(id = "b"))),
                                  base.copy(cases = listOf(base.cases[0].copy(id = "anders"))))) {
            val next = incomplete.copy(revision = 6)
            assertFalse(CatalogUpdatePolicy.canRefresh(base, next))
            assertFalse(CatalogUpdatePolicy.canUseAtStartup(base, next))
        }
    }

    @Test fun `older or conflicting equal revisions cannot override the packaged catalog`() {
        assertFalse(CatalogUpdatePolicy.canUseAtStartup(base, base.copy(revision = 4)))
        assertTrue(CatalogUpdatePolicy.canUseAtStartup(base, base.copy()))
        assertFalse(CatalogUpdatePolicy.canUseAtStartup(base, base.copy(notes = listOf(base.notes[0].copy(short = "anders")))))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, base))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, base.copy(revision = 4)))
    }

    @Test fun `policy checks identity not medical correctness or text equality`() {
        val corrected = base.copy(revision = 6, cards = listOf(base.cards[0].copy(back = "anderer Text")))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, corrected))
    }
}
