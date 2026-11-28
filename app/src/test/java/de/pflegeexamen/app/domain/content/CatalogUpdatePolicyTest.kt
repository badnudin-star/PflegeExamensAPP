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

    @Test fun `explicit cumulative retirement permits replacement but not silent removal or revival`() {
        val replacement = base.copy(revision = 6, cards = listOf(base.cards[0].copy(id = "new")), retiredCardIds = listOf("a"))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, replacement))
        assertTrue(CatalogUpdatePolicy.canUseAtStartup(base, replacement))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, replacement.copy(retiredCardIds = emptyList())))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, replacement.copy(retiredCardIds = listOf("a", "new"))))
        val later = replacement.copy(revision = 7, cards = listOf(base.cards[0].copy(id = "newer")), retiredCardIds = listOf("a", "new"))
        assertTrue(CatalogUpdatePolicy.canRefresh(replacement, later))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, later)) // a device may skip releases
        assertFalse(CatalogUpdatePolicy.canRefresh(replacement, later.copy(retiredCardIds = listOf("new"))))
        assertFalse(CatalogUpdatePolicy.canRefresh(replacement, later.copy(cards = base.cards, retiredCardIds = listOf("new"))))
        assertFalse(CatalogUpdatePolicy.canRefresh(replacement, later.copy(cases = emptyList())))
    }

    @Test fun `case retirement is explicit cumulative and cannot revive old progress`() {
        val next = base.copy(revision = 6, cases = listOf(base.cases[0].copy(id = "case-v2-new")), retiredCaseIds = listOf("fall"))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, next))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, next.copy(retiredCaseIds = emptyList())))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, next.copy(retiredCaseIds = listOf("fall", "case-v2-new"))))
        val later = next.copy(revision = 7, cases = listOf(base.cases[0].copy(id = "case-v2-next")), retiredCaseIds = listOf("fall", "case-v2-new"))
        assertTrue(CatalogUpdatePolicy.canRefresh(next, later))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, later))
        assertFalse(CatalogUpdatePolicy.canRefresh(next, later.copy(retiredCaseIds = listOf("case-v2-new"))))
        assertFalse(CatalogUpdatePolicy.canRefresh(next, later.copy(cards = emptyList())))
    }

    @Test fun `older or conflicting equal revisions cannot override the packaged catalog`() {
        assertFalse(CatalogUpdatePolicy.canUseAtStartup(base, base.copy(revision = 4)))
        assertTrue(CatalogUpdatePolicy.canUseAtStartup(base, base.copy()))
        assertFalse(CatalogUpdatePolicy.canUseAtStartup(base, base.copy(notes = listOf(base.notes[0].copy(short = "anders")))))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, base))
        assertFalse(CatalogUpdatePolicy.canRefresh(base, base.copy(revision = 4)))
    }

    @Test fun `refresh decisions distinguish unchanged older conflicting and incomplete snapshots`() {
        assertEquals(RefreshDecision.UP_TO_DATE, CatalogUpdatePolicy.decideRefresh(base, base.copy()))
        assertEquals(RefreshDecision.SERVER_OLDER, CatalogUpdatePolicy.decideRefresh(base, base.copy(revision = 4)))
        assertEquals(RefreshDecision.REJECTED, CatalogUpdatePolicy.decideRefresh(base, base.copy(cards = emptyList())))
        assertEquals(RefreshDecision.REJECTED, CatalogUpdatePolicy.decideRefresh(base, base.copy(revision = 6, cases = emptyList())))
        assertEquals(RefreshDecision.UPDATE, CatalogUpdatePolicy.decideRefresh(base, base.copy(revision = 6)))
    }

    @Test fun `higher revision accepts updated cards and cases without changing identities`() {
        val updated = base.copy(
            revision = 6,
            cards = listOf(base.cards[0].copy(front = "Neue Frage", back = "Neue Antwort")),
            cases = listOf(base.cases[0].copy(text = "Neue Fallsituation", questions = listOf(
                base.cases[0].questions[0].copy(answer = "Neue Musterlösung")
            )))
        )
        assertEquals(RefreshDecision.UPDATE, CatalogUpdatePolicy.decideRefresh(base, updated))
        assertTrue(CatalogUpdatePolicy.canUseAtStartup(base, updated))
        assertEquals("Neue Antwort", updated.cards[0].back)
        assertEquals("Neue Musterlösung", updated.cases[0].questions[0].answer)
    }

    @Test fun `policy checks identity not medical correctness or text equality`() {
        val corrected = base.copy(revision = 6, cards = listOf(base.cards[0].copy(back = "anderer Text")))
        assertTrue(CatalogUpdatePolicy.canRefresh(base, corrected))
    }
}
