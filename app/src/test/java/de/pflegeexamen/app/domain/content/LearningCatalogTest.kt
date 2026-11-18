package de.pflegeexamen.app.domain.content

import de.pflegeexamen.app.domain.model.DefaultCatalogs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LearningCatalogTest {
    private val topic = DefaultCatalogs.defaultPreparations.first().title
    private val source = listOf("Eigene Quelle, S. 1")
    private val complete = LearningCatalog(
        revision = 1,
        notes = listOf(PublishedNote(topic, "Kurz", "Ausgewogen", "Ausführlich", source)),
        cards = listOf(PublishedCard("card-1", topic, "Frage", "Antwort", source)),
        cases = listOf(PublishedCase("case-1", "2026-11-01", listOf("Krankheitsbild"), "Falltext", source, listOf(PublishedCaseQuestion("Frage", "Antwort", source)))),
        quizzes = listOf(PublishedQuiz("q-1", "2026-11-01", topic, "Frage?", listOf("A", "B", "C", "D"), 1, "Erklärung", source))
    )
    private fun parse(c: LearningCatalog) = LearningCatalogCodec.parse(Json.encodeToString(c))

    @Test fun `valid complete and empty bundles are accepted`() {
        assertNotNull(parse(complete))
        assertNotNull(parse(LearningCatalog()))
    }
    @Test fun `bundled notes cover every fixed topic and all three lengths`() {
        val raw = java.io.File("src/main/assets/learning_content.json").readText()
        val catalog = requireNotNull(LearningCatalogCodec.parse(raw))
        assertEquals(DefaultCatalogs.defaultPreparations.map { it.title }, catalog.notes.map { it.topic })
        assertTrue(catalog.notes.all { it.short.isNotBlank() && it.balanced.isNotBlank() && it.detailed.isNotBlank() && it.sources.size == 3 })
        assertTrue(catalog.notes.all { "Quellen und Einordnung" in it.short && "Quellen und Einordnung" in it.balanced && "Quellen und Einordnung" in it.detailed })
    }
    @Test fun `bundled cards cover all fixed topics without duplicates`() {
        val catalog = requireNotNull(LearningCatalogCodec.parse(java.io.File("src/main/assets/learning_content.json").readText()))
        assertEquals(523, catalog.cards.size)
        assertEquals(DefaultCatalogs.defaultPreparations.map { it.title }.toSet(), catalog.cards.map { it.topic }.toSet())
        assertEquals(523, catalog.cards.map { it.id }.distinct().size)
        assertTrue(catalog.cards.all { it.front.isNotBlank() && it.back.isNotBlank() && it.sources.size == 2 })
    }
    @Test fun `bundled cases are grouped by exam day and questions have answers`() {
        val catalog = requireNotNull(LearningCatalogCodec.parse(java.io.File("src/main/assets/learning_content.json").readText()))
        assertEquals(30, catalog.cases.size)
        assertEquals(494, catalog.cases.sumOf { it.questions.size })
        assertEquals(listOf(10, 10, 10), (1..3).map { day -> catalog.cases.count { it.examDay == day } })
        assertTrue(catalog.cases.all { it.title.isNotBlank() && it.questions.all { q -> q.question.isNotBlank() && q.answer.isNotBlank() } })
    }
    @Test fun `a publication without source or valid answers is rejected`() {
        assertNull(parse(complete.copy(cards = listOf(complete.cards.first().copy(sources = emptyList())))))
        assertNull(parse(complete.copy(quizzes = listOf(complete.quizzes.first().copy(correctIndex = 5)))))
        assertNull(parse(complete.copy(quizzes = listOf(complete.quizzes.first().copy(options = listOf("A", "A", "C", "D"))))))
        assertNull(parse(complete.copy(cases = listOf(complete.cases.first().copy(sources = emptyList())))))
    }
    @Test fun `unknown topics duplicates and incompatible schemas are rejected`() {
        assertNull(parse(complete.copy(notes = listOf(complete.notes.first().copy(topic = "Nicht auf der Liste")))))
        assertNull(parse(complete.copy(cards = complete.cards + complete.cards)))
        assertNull(parse(complete.copy(schemaVersion = 2)))
        assertNull(LearningCatalogCodec.parse("{broken}"))
    }
}
