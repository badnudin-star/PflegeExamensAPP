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
