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
    @Test fun `bundled notes cover every fixed topic with complete supported variants`() {
        val raw = java.io.File("src/main/assets/learning_content.json").readText()
        val catalog = requireNotNull(LearningCatalogCodec.parse(raw))
        assertEquals(DefaultCatalogs.defaultPreparations.map { it.title }, catalog.notes.map { it.topic })
        assertTrue(catalog.notes.all { it.short.isNotBlank() && it.balanced.isNotBlank() && it.detailed.isNotBlank() })
        assertTrue(catalog.notes.all { note ->
            when (note.formatVersion) {
                1 -> note.sources.size == 3 && "Quellen und Einordnung" in note.short && "Quellen und Einordnung" in note.detailed
                2 -> note.sources.size == 2 && note.balanced == note.detailed &&
                    note.short.contains("QUELLEN", ignoreCase = true) && note.detailed.contains("QUELLEN", ignoreCase = true)
                else -> false
            }
        })
    }
    @Test fun `bundled cards cover all fixed topics without duplicates`() {
        val catalog = requireNotNull(LearningCatalogCodec.parse(java.io.File("src/main/assets/learning_content.json").readText()))
        assertTrue(catalog.cards.isNotEmpty())
        assertEquals(DefaultCatalogs.defaultPreparations.map { it.title }.toSet(), catalog.cards.map { it.topic }.toSet())
        assertEquals(catalog.cards.size, catalog.cards.map { it.id }.distinct().size)
        assertTrue(catalog.cards.all { it.front.isNotBlank() && it.back.isNotBlank() && it.sources.isNotEmpty() })
    }
    @Test fun `bundled cases are grouped by exam day and questions have answers`() {
        val catalog = requireNotNull(LearningCatalogCodec.parse(java.io.File("src/main/assets/learning_content.json").readText()))
        assertTrue(catalog.cases.isNotEmpty())
        assertTrue((1..3).all { day -> catalog.cases.any { it.examDay == day } })
        assertTrue(catalog.cases.all { it.title.isNotBlank() && it.questions.isNotEmpty() && it.questions.all { q -> q.question.isNotBlank() && q.answer.isNotBlank() } })
    }
    @Test fun `a publication without source or valid answers is rejected`() {
        assertNull(parse(complete.copy(cards = listOf(complete.cards.first().copy(sources = emptyList())))))
        assertNull(parse(complete.copy(quizzes = listOf(complete.quizzes.first().copy(correctIndex = 5)))))
        assertNull(parse(complete.copy(quizzes = listOf(complete.quizzes.first().copy(options = listOf("A", "A", "C", "D"))))))
        assertNull(parse(complete.copy(cases = listOf(complete.cases.first().copy(sources = emptyList())))))
    }
    @Test fun `retired ids must be unique nonblank and inactive`() {
        assertNotNull(parse(complete.copy(retiredCardIds = listOf("old"))))
        assertNull(parse(complete.copy(retiredCardIds = listOf(""))))
        assertNull(parse(complete.copy(retiredCardIds = listOf("old", "old"))))
        assertNull(parse(complete.copy(retiredCardIds = listOf("card-1"))))
    }
    @Test fun `unknown topics duplicates and incompatible schemas are rejected`() {
        assertNull(parse(complete.copy(notes = listOf(complete.notes.first().copy(topic = "Nicht auf der Liste")))))
        assertNull(parse(complete.copy(cards = complete.cards + complete.cards)))
        assertNull(parse(complete.copy(schemaVersion = 2)))
        assertNull(LearningCatalogCodec.parse("{broken}"))
    }
}
