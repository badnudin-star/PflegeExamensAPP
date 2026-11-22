package de.pflegeexamen.app.domain.notes

import de.pflegeexamen.app.domain.content.PublishedNote

/** Search exactly the blocks displayed by NoteScreen, without exposing hidden source sections. */
data class NoteSearchHit(val main: Boolean, val simple: Boolean)

class NoteSearchIndex(notes: List<PublishedNote>) {
    private data class Entry(val topic: String, val main: List<NoteBlock>, val simple: List<NoteBlock>)
    private val entries = notes.asSequence().filter { it.formatVersion == 2 }
        .distinctBy { it.topic }
        .map { Entry(it.topic, NotePresentation.blocks(it.detailed), NotePresentation.blocks(it.short)) }
        .toList()

    /** An empty query has no content hits; callers can still show all topics. */
    fun search(query: String): Map<String, NoteSearchHit> {
        if (query.isBlank()) return emptyMap()
        return entries.mapNotNull { entry ->
            val hit = NoteSearchHit(NotePresentation.firstMatch(entry.main, query) != null,
                NotePresentation.firstMatch(entry.simple, query) != null)
            if (hit.main || hit.simple) entry.topic to hit else null
        }.toMap()
    }
}
