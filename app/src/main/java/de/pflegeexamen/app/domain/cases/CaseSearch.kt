package de.pflegeexamen.app.domain.cases

import de.pflegeexamen.app.domain.content.PublishedCase

/** Labels indicate only where text matched, never the contents of a hidden answer. */
enum class CaseSearchField(val label: String) {
    TITLE("Titel/Krankheitsbild"), TEXT("Falltext"), INSTRUCTIONS("Hinweise"),
    QUESTION("Aufgaben"), ANSWER("Antworten")
}

data class CaseSearchResult(val case: PublishedCase, val fields: List<CaseSearchField>)

/** In-memory local index. Sources, completion state and catalog data are not modified. */
class CaseSearchIndex(cases: List<PublishedCase>) {
    private data class Entry(val case: PublishedCase, val fields: List<Pair<CaseSearchField, List<String>>>)
    private val entries = cases.map { case ->
        Entry(case, listOf(
            CaseSearchField.TITLE to listOf(case.title).plus(case.diseases),
            CaseSearchField.TEXT to listOf(case.text),
            CaseSearchField.INSTRUCTIONS to listOf(case.instructions, case.answerNote),
            CaseSearchField.QUESTION to case.questions.map { it.question },
            CaseSearchField.ANSWER to case.questions.map { it.answer }
        ).map { (field, values) -> field to values.map(::normalize) })
    }

    fun search(query: String): List<CaseSearchResult> {
        val term = normalize(query)
        return entries.mapNotNull { entry ->
            if (term.isEmpty()) return@mapNotNull CaseSearchResult(entry.case, emptyList())
            val fields = entry.fields.filter { (_, values) -> values.any { it.contains(term, ignoreCase = true) } }.map { it.first }
            if (fields.isEmpty()) null else CaseSearchResult(entry.case, fields)
        }
    }

    companion object {
        private val whitespace = Regex("[\\s\\p{Z}]+")
        /** Treat PDF line breaks and consecutive whitespace as spaces; do not rewrite the content. */
        fun normalize(value: String): String = value.replace(whitespace, " ").trim()
    }
}
