package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSourceMatcherTest {
    private val mixedSource = listOf(
        ChunkInput(1, 10, "Pflegeskript", SourceType.PDF, 1, "Herzinsuffizienz", "Bei Herzinsuffizienz kann die Pumpleistung des Herzens vermindert sein."),
        ChunkInput(2, 10, "Pflegeskript", SourceType.PDF, 2, "Händehygiene", "Händedesinfektion schützt vor Infektionen und ist Teil der Pflege."),
        ChunkInput(3, 11, "Herzanatomie", SourceType.PDF, 3, "Herz", "Das Herz pumpt das Blut durch den Körperkreislauf.")
    )

    @Test
    fun `finds related sections across documents without modifying the input`() {
        val before = mixedSource.map { it.copy() }
        val result = TopicSourceMatcher.match(
            mixedSource, "Herzinsuffizienz", listOf("Herz", "Anatomie", "Physiologie", "Pflege"),
            listOf("Anatomie & Physiologie"), DetailDepth.EXAM_DEEP
        ) as RetrievalOutcome.Found
        assertEquals(setOf(1L, 3L), result.passages.map { it.chunk.chunkId }.toSet())
        assertEquals(before, mixedSource)
        assertEquals(3, result.passages.first { it.chunk.chunkId == 3L }.chunk.pageNumber)
    }

    @Test
    fun `generic nursing terms alone do not link unrelated passages`() {
        val unrelated = listOf(mixedSource[1])
        val result = TopicSourceMatcher.match(unrelated, "Herzinsuffizienz", listOf("Pflege", "Anatomie", "Physiologie"))
        assertTrue(result is RetrievalOutcome.NoMatches)
    }

    @Test
    fun `new imports become searchable without recreating the topic`() {
        val first = listOf(mixedSource[0])
        val before = TopicSourceMatcher.match(first, "Herzinsuffizienz", listOf("Herz")) as RetrievalOutcome.Found
        val after = TopicSourceMatcher.match(first + mixedSource[2], "Herzinsuffizienz", listOf("Herz")) as RetrievalOutcome.Found
        assertEquals(setOf(10L), before.passages.map { it.chunk.sourceId }.toSet())
        assertEquals(setOf(10L, 11L), after.passages.map { it.chunk.sourceId }.toSet())
    }
}
