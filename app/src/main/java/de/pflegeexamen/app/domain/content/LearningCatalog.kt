package de.pflegeexamen.app.domain.content

import de.pflegeexamen.app.domain.model.DefaultCatalogs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LearningCatalog(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val notes: List<PublishedNote> = emptyList(),
    val cards: List<PublishedCard> = emptyList(),
    val cases: List<PublishedCase> = emptyList(),
    val quizzes: List<PublishedQuiz> = emptyList(),
    // Tombstones are cumulative, so devices skipping revisions can safely retire cards.
    val retiredCardIds: List<String> = emptyList()
)

@Serializable
data class PublishedNote(val topic: String, val short: String, val balanced: String, val detailed: String, val sources: List<String>, val formatVersion: Int = 1)
@Serializable
data class PublishedCard(val id: String, val topic: String, val front: String, val back: String, val sources: List<String>)
@Serializable
data class PublishedCaseQuestion(val question: String, val answer: String, val sources: List<String>)
@Serializable
data class PublishedCase(val id: String, val published: String, val diseases: List<String>, val text: String, val sources: List<String>, val questions: List<PublishedCaseQuestion>, val examDay: Int? = null, val title: String = "", val instructions: String = "", val answerNote: String = "")
@Serializable
data class PublishedQuiz(val id: String, val published: String, val topic: String, val question: String, val options: List<String>, val correctIndex: Int, val explanation: String, val sources: List<String>)

/** Reject an entire invalid publication; never replace a good offline copy with partial data. */
object LearningCatalogCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(raw: String): LearningCatalog? {
        val catalog = runCatching { json.decodeFromString<LearningCatalog>(raw) }.getOrNull() ?: return null
        if (catalog.schemaVersion != 1 || catalog.revision < 0) return null
        val titles = DefaultCatalogs.defaultPreparations.map { it.title }.toSet()
        fun hasSources(sources: List<String>) = sources.isNotEmpty() && sources.all { it.isNotBlank() }
        fun <T> unique(items: List<T>, key: (T) -> String): Boolean = items.map(key).let { values -> values.all { it.isNotBlank() } && values.size == values.toSet().size }
        if (!unique(catalog.notes) { it.topic } || catalog.notes.any { it.topic !in titles || it.short.isBlank() || it.balanced.isBlank() || it.detailed.isBlank() || !hasSources(it.sources) }) return null
        if (!unique(catalog.cards) { it.id } || catalog.cards.any { it.topic !in titles || it.front.isBlank() || it.back.isBlank() || !hasSources(it.sources) }) return null
        val retired = catalog.retiredCardIds
        val retiredSet = retired.toSet()
        if (retired.any { it.isBlank() } || retired.size != retiredSet.size ||
            catalog.cards.any { it.id in retiredSet }) return null
        if (!unique(catalog.cases) { it.id } || catalog.cases.any { c -> c.published.isBlank() || c.text.isBlank() || !hasSources(c.sources) || c.diseases.isEmpty() || c.diseases.any { it.isBlank() } || c.questions.isEmpty() || c.questions.any { it.question.isBlank() || it.answer.isBlank() || !hasSources(it.sources) } }) return null
        if (!unique(catalog.quizzes) { it.id } || catalog.quizzes.any { q -> q.published.isBlank() || q.topic !in titles || q.question.isBlank() || q.options.size != 4 || q.options.any { it.isBlank() } || q.options.distinct().size != 4 || q.correctIndex !in 0..3 || q.explanation.isBlank() || !hasSources(q.sources) }) return null
        return catalog
    }
}
