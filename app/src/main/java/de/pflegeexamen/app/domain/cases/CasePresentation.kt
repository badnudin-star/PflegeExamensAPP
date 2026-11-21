package de.pflegeexamen.app.domain.cases

import de.pflegeexamen.app.domain.notes.NoteSection
import de.pflegeexamen.app.domain.model.DefaultCatalogs

/** Display-only formatting for extracted case PDFs. No source text is modified. */
object CasePresentation {
    /** Day grouping is not a claim that every listed topic applies to this case. */
    fun topicIndicesForDay(day: Int?): List<Int> {
        if (day == null || day !in 1..3) return emptyList()
        return DefaultCatalogs.defaultPreparations.withIndex()
            .filter { it.value.examDayTag == "Tag $day" }.map { it.index }
    }

    fun sections(text: String): List<NoteSection> {
        val lines = text.replace("\r\n", "\n").lines().map { it.trim() }
        val sections = mutableListOf<NoteSection>()
        val paragraphs = mutableListOf<String>()
        var heading: String? = null
        var paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) paragraphs.add(paragraph.toString().trim())
            paragraph = StringBuilder()
        }
        fun flushSection() {
            flushParagraph()
            if (heading != null || paragraphs.isNotEmpty()) sections.add(NoteSection(heading, paragraphs.toList()))
            paragraphs.clear(); heading = null
        }
        for (i in lines.indices) {
            val line = lines[i]
            val previous = lines.getOrNull(i - 1).orEmpty()
            val next = lines.getOrNull(i + 1).orEmpty()
            val isHeading = line == "FALLSITUATION" || (
                line.length in 3..65 && line.first().isUpperCase() && !line.endsWith('.') && !line.endsWith(':') &&
                previous.lastOrNull() in listOf('.', '!', '?', '“', '”') && next.length > line.length && next.firstOrNull()?.isUpperCase() == true
            )
            when {
                line.isBlank() -> flushParagraph()
                isHeading -> { flushSection(); heading = line }
                else -> {
                    if (paragraph.isNotEmpty() && previous.lastOrNull() in listOf('.', '!', '?', '“', '”') && line.first().isUpperCase()) flushParagraph()
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line)
                }
            }
        }
        flushSection()
        return sections
    }
}
