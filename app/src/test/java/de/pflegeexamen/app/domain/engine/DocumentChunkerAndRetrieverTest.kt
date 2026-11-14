package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerAndRetrieverTest {
    @Test
    fun `incremental chunking keeps heading and handles long unbroken text`() {
        val session = DocumentChunker.Session()
        val first = session.chunkPage(RawPage(1, "# Herzinsuffizienz\n\nDie Herzinsuffizienz ist eine Erkrankung."))
        val second = session.chunkPage(RawPage(2, "K".repeat(10_000)))
        assertTrue(second.size > 1)
        assertTrue(second.all { it.content.length <= 1050 })
        assertTrue(second.all { it.sectionHeading == "Herzinsuffizienz" })
        assertEquals(first.size, second.first().chunkIndex)
        assertEquals(10_000, second.sumOf { it.content.length })
        val awkwardLength = DocumentChunker.chunkPages(listOf(RawPage(1, "A".repeat(1_561))))
        assertEquals(1_561, awkwardLength.sumOf { it.content.length })
    }

    @Test
    fun `explicit topic links include physiology without disease terms and exclude other topics`() {
        val chunks = listOf(
            ChunkInput(1, 10, "Herz", SourceType.TXT, 1, "Kreislauf", "Das Herz pumpt Blut durch den Kreislauf."),
            ChunkInput(2, 20, "Hygiene", SourceType.TXT, 1, "Hände", "Händedesinfektion unterbricht Infektionsketten.")
        )
        val result = SourceRetriever.retrieveAssigned(chunks, "Herzinsuffizienz", listOf("NYHA"), selectedSourceIds = setOf(10)) as RetrievalOutcome.Found
        assertEquals(listOf(10L), result.passages.map { it.chunk.sourceId })
        assertTrue(SourceRetriever.retrieveAssigned(chunks, "Herzinsuffizienz", listOf("NYHA"), selectedSourceIds = setOf(99)) is RetrievalOutcome.NoSources)
    }


    @Test
    fun `chunkPages preserves page numbers and headings`() {
        val pages = listOf(
            RawPage(
                pageNumber = 4,
                text = """
                    1.1 Dekubitusprophylaxe
                    Unter einem Dekubitus versteht man eine lokal begrenzte Schädigung der Haut.
                    - Risikoerfassung mittels Bradenskala durchführen.
                    - 30°-Seitenlagerung zur Druckentlastung anwenden.
                """.trimIndent()
            )
        )
        val chunks = DocumentChunker.chunkPages(pages)
        assertEquals(1, chunks.size)
        assertEquals(4, chunks.first().pageNumber)
        assertEquals("1.1 Dekubitusprophylaxe", chunks.first().sectionHeading)
    }

    @Test
    fun `retriever returns NoSources when no sources exist`() {
        val result = SourceRetriever.retrieve(
            allChunks = emptyList(),
            titleQuery = "Dekubitus",
            keywords = listOf("Druckgeschwür")
        )
        assertTrue(result is RetrievalOutcome.NoSources)
    }

    @Test
    fun `retriever returns NoMatches instead of hallucinating for unknown topics`() {
        val chunks = listOf(
            ChunkInput(
                chunkId = 1,
                sourceId = 1,
                sourceTitle = "Unterrichtsskript Hygiene.pdf",
                sourceType = SourceType.PDF,
                pageNumber = 2,
                sectionHeading = "Händedesinfektion",
                content = "Die hygienische Händedesinfektion dauert 30 Sekunden mit alkoholischem Präparat."
            )
        )
        val result = SourceRetriever.retrieve(
            allChunks = chunks,
            titleQuery = "Herzinsuffizienz",
            keywords = listOf("NYHA", "Ödeme")
        )
        assertTrue(result is RetrievalOutcome.NoMatches)
    }

    @Test
    fun `retriever finds matching chunks and respects selectedSourceIds`() {
        val chunks = listOf(
            ChunkInput(
                chunkId = 1,
                sourceId = 10,
                sourceTitle = "Skript Dekubitus.pdf",
                sourceType = SourceType.PDF,
                pageNumber = 5,
                sectionHeading = "Maßnahmen",
                content = "Die 30-Grad-Lagerung entlastet gefährdete Hautareale, weil der Auflagedruck reduziert wird."
            ),
            ChunkInput(
                chunkId = 2,
                sourceId = 20,
                sourceTitle = "Skript Ernährung.pdf",
                sourceType = SourceType.PDF,
                pageNumber = 1,
                sectionHeading = "Mangelernährung",
                content = "Mangelernährung verzögert die Wundheilung."
            )
        )

        val scoped = SourceRetriever.retrieve(
            allChunks = chunks,
            titleQuery = "Dekubitusprophylaxe",
            keywords = listOf("Lagerung"),
            selectedSourceIds = setOf(20),
            detailDepth = DetailDepth.STANDARD
        )
        assertTrue(scoped is RetrievalOutcome.NoMatches)

        val unscoped = SourceRetriever.retrieve(
            allChunks = chunks,
            titleQuery = "Dekubitusprophylaxe",
            keywords = listOf("Lagerung"),
            selectedSourceIds = emptySet(),
            detailDepth = DetailDepth.STANDARD
        )
        assertTrue(unscoped is RetrievalOutcome.Found)
        val found = unscoped as RetrievalOutcome.Found
        assertEquals(10L, found.passages.first().chunk.sourceId)
    }

    @Test
    fun `mapParametersToRoles maps anatomy physiology and combined parameters to all matching roles`() {
        val roles = SourceRetriever.mapParametersToRoles(
            listOf(
                "Anatomie & Physiologie",
                "Pflegemaßnahmen & Begründungen"
            )
        )
        assertTrue(de.pflegeexamen.app.domain.model.SentenceRole.DEFINITION in roles)
        assertTrue(de.pflegeexamen.app.domain.model.SentenceRole.INTERVENTION_MEASURE in roles)
        assertTrue(de.pflegeexamen.app.domain.model.SentenceRole.REASON_GOAL in roles)
    }
}
