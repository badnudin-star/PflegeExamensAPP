package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyNoteAndFlashcardGeneratorTest {

    private val sampleChunks = listOf(
        ChunkInput(
            chunkId = 1,
            sourceId = 1,
            sourceTitle = "Skript Wundmanagement.pdf",
            sourceType = SourceType.PDF,
            pageNumber = 12,
            sectionHeading = "Dekubitus",
            content = """
                Unter einem Dekubitus versteht man eine lokal begrenzte Schädigung der Haut und des darunterliegenden Gewebes.
                Hauptursache ist eine länger anhaltende Druckeinwirkung in Kombination mit Scherkräften.
                Im Fingertest bleibt die Rötung bei Grad 1 nicht wegdrückbar.
                Die 30-Grad-Seitenlagerung wird durchgeführt, weil dadurch knöcherne Vorsprünge entlastet werden.
                Cave: Keinesfalls Frottee-Kissen oder Wasserringe verwenden, da diese den Druck punktuell erhöhen.
                - Hautinspektion bei jedem Positionswechsel durchführen
                - Bewegungsplan individuell an das Risiko nach Bradenskala anpassen
                - Ausreichende Flüssigkeits- und Eiweißzufuhr sicherstellen
            """.trimIndent()
        )
    )

    @Test
    fun `StudyNoteGenerator structures sections by user parameters with exact citations`() {
        val config = PrepGenerationConfig(
            prepItemId = 1,
            title = "Dekubitus",
            categoryOrCompetenceArea = "KB I",
            keywords = listOf("Dekubitus", "Druckgeschwür", "Bradenskala"),
            detailDepth = DetailDepth.EXAM_DEEP,
            focusParameters = listOf(
                "Definition & Grundbegriffe",
                "Ursachen & Risikofaktoren",
                "Pflegemaßnahmen",
                "Merksätze & Cave"
            ),
            targetFlashcardCount = 8,
            flashcardTypes = FlashcardType.entries.toSet(),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )

        val retrieval = SourceRetriever.retrieve(
            allChunks = sampleChunks,
            titleQuery = config.title,
            keywords = config.keywords,
            focusParameters = config.focusParameters,
            detailDepth = config.detailDepth
        ) as RetrievalOutcome.Found

        val note = StudyNoteGenerator.generate(config, retrieval.passages)
        assertNotNull(note)
        assertTrue(note!!.sections.any { it.parameterTitle == "Definition & Grundbegriffe" })
        assertTrue(note.sections.any { it.parameterTitle == "Pflegemaßnahmen" })
        assertTrue(note.summaryMarkdown.contains("Skript Wundmanagement.pdf"))
        assertTrue(note.summaryMarkdown.contains("S. 12"))
    }

    @Test
    fun `FlashcardGenerator produces varied deduplicated cards and SM2 schedules correctly`() {
        val config = PrepGenerationConfig(
            prepItemId = 1,
            title = "Dekubitus",
            categoryOrCompetenceArea = "",
            keywords = listOf("Druckeinwirkung", "Lagerung"),
            detailDepth = DetailDepth.STANDARD,
            focusParameters = listOf("Definition", "Pflegemaßnahmen"),
            targetFlashcardCount = 10,
            flashcardTypes = FlashcardType.entries.toSet(),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )

        val retrieval = SourceRetriever.retrieve(
            allChunks = sampleChunks,
            titleQuery = config.title,
            keywords = config.keywords
        ) as RetrievalOutcome.Found

        val cards = FlashcardGenerator.generate(config, retrieval.passages)
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.any { it.cardType == FlashcardType.DEFINITION })
        assertTrue(cards.any { it.cardType == FlashcardType.LIST })
        assertTrue(cards.any { it.cardType == FlashcardType.CAVE })
        assertTrue(cards.all { it.citationLabel.contains("Skript Wundmanagement.pdf") })

        // Test SM-2 progression
        val r1 = SpacedRepetition.calculateNext(0, 0, 2.5f, quality = 4, nowMillis = 1_000_000L)
        assertEquals(1, r1.repetitions)
        assertEquals(1, r1.intervalDays)

        val r2 = SpacedRepetition.calculateNext(r1.repetitions, r1.intervalDays, r1.easeFactor, quality = 4, nowMillis = 2_000_000L)
        assertEquals(2, r2.repetitions)
        assertEquals(6, r2.intervalDays)

        val reset = SpacedRepetition.calculateNext(r2.repetitions, r2.intervalDays, r2.easeFactor, quality = 1, nowMillis = 3_000_000L)
        assertEquals(0, reset.repetitions)
        assertEquals(0, reset.intervalDays)
    }

    @Test
    fun `StudyNoteGenerator matches custom user-defined parameter keywords`() {
        val config = PrepGenerationConfig(
            prepItemId = 2,
            title = "Dekubitus",
            categoryOrCompetenceArea = "",
            keywords = listOf("Fingertest", "Bradenskala"),
            detailDepth = DetailDepth.STANDARD,
            focusParameters = listOf("Fingertest & Bradenskala"),
            targetFlashcardCount = 5,
            flashcardTypes = setOf(FlashcardType.CLOZE),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )
        val retrieval = SourceRetriever.retrieve(
            allChunks = sampleChunks,
            titleQuery = config.title,
            keywords = config.keywords,
            focusParameters = config.focusParameters
        ) as RetrievalOutcome.Found

        val note = StudyNoteGenerator.generate(config, retrieval.passages)
        assertNotNull(note)
        assertTrue(note!!.sections.any { it.parameterTitle == "Fingertest & Bradenskala" })
    }

    @Test
    fun `StudyNoteGenerator does not fabricate section for unmatched custom parameter`() {
        val config = PrepGenerationConfig(
            prepItemId = 3,
            title = "Dekubitus",
            categoryOrCompetenceArea = "",
            keywords = listOf("Dekubitus", "Bradenskala"),
            detailDepth = DetailDepth.STANDARD,
            focusParameters = listOf("Dialysepflicht", "Wasserringe"),
            targetFlashcardCount = 4,
            flashcardTypes = setOf(FlashcardType.CAVE),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )
        val retrieval = SourceRetriever.retrieve(
            allChunks = sampleChunks,
            titleQuery = config.title,
            keywords = config.keywords,
            focusParameters = config.focusParameters
        ) as RetrievalOutcome.Found

        val note = StudyNoteGenerator.generate(config, retrieval.passages)
        assertNotNull(note)
        assertTrue(note!!.sections.none { it.parameterTitle == "Dialysepflicht" })
        assertTrue(note.sections.any { it.parameterTitle == "Wasserringe" })
    }
}
