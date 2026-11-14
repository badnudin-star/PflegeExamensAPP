package de.pflegeexamen.app.domain.ai

import de.pflegeexamen.app.domain.engine.DocumentChunker
import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.*
import org.junit.Test

class SourceEvidenceSelectorTest {
    private fun passage(id: Long, text: String) = RetrievedPassage(
        ChunkInput(id, id, "Quelle $id", SourceType.PDF, id.toInt(), "Abschnitt", text),
        2.0, setOf("pflege"), DocumentChunker.classifyChunkSentences(text)
    )

    @Test fun `selection is split across batches and retains exact source and page`() {
        val inputs = (1L..8L).map { id ->
            passage(id, (1..4).joinToString(" ") { "Aussage $it zu Pflege $id mit genügend zusätzlichem Text für die Auswahl und Prüfung." })
        }
        val plan = SourceEvidenceSelector.plan(inputs) as SourceEvidenceSelector.Plan.Ready
        assertTrue(plan.batches.size > 1)
        val result = plan.batches.flatMap { batch ->
            val prompt = SourceEvidenceSelector.prompt("Pflege", listOf("Risiken"), batch)
            assertTrue(prompt.systemPrompt.length + prompt.userPrompt.length + 2 <= 3500)
            SourceEvidenceSelector.select("{\"ids\":[${batch.candidates.joinToString(",") { it.id.toString() }}]}", batch)!!
        }
        assertEquals(plan.batches.sumOf { it.candidates.size }, result.size)
        result.forEach { selected ->
            val original = inputs.first { it.chunk.sourceId == selected.chunk.sourceId }
            assertTrue(original.chunk.content.contains(selected.chunk.content))
            assertEquals(original.chunk.pageNumber, selected.chunk.pageNumber)
            assertEquals(original.chunk.sectionHeading, selected.chunk.sectionHeading)
        }
    }

    @Test fun `invalid missing duplicate and foreign ids cannot become citations`() {
        val plan = SourceEvidenceSelector.plan(listOf(passage(1, "Dekubitus entsteht durch anhaltenden Druck auf das Gewebe."))) as SourceEvidenceSelector.Plan.Ready
        val batch = plan.batches.single()
        assertNull(SourceEvidenceSelector.select("{}", batch))
        assertNull(SourceEvidenceSelector.select("{\"ids\":[999]}", batch))
        assertNull(SourceEvidenceSelector.select("{\"ids\":[1,1]}", batch))
        assertNull(SourceEvidenceSelector.select("not json", batch))
        assertTrue(SourceEvidenceSelector.select("{\"ids\":[]}", batch)!!.isEmpty())
    }

    @Test fun `unverifiable or oversized statements are not silently treated as evidence`() {
        val p = passage(1, "Langtext " + "a".repeat(2500) + ".")
        val plan = SourceEvidenceSelector.plan(listOf(p)) as SourceEvidenceSelector.Plan.Ready
        assertTrue(plan.batches.isEmpty())
        assertEquals(1, plan.skippedSentences)
    }
}
