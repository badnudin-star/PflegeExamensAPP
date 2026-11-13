package de.pflegeexamen.app.domain.ai

import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptAndParserTest {

    private val passages = listOf(
        RetrievedPassage(
            chunk = ChunkInput(
                chunkId = 11L,
                sourceId = 1L,
                sourceTitle = "PflegeHeute Dekubitus",
                sourceType = SourceType.PDF,
                pageNumber = 142,
                sectionHeading = "Pathophysiologie",
                content = "Ein Dekubitus entsteht durch anhaltenden Auflagedruck, der die kapilläre Durchblutung mindert. Cave: Scherkräfte verstärken die Gewebeschädigung zusätzlich."
            ),
            score = 1.0,
            matchedTerms = setOf("dekubitus", "auflagedruck"),
            classifiedSentences = emptyList()
        ),
        RetrievedPassage(
            chunk = ChunkInput(
                chunkId = 12L,
                sourceId = 1L,
                sourceTitle = "PflegeHeute Dekubitus",
                sourceType = SourceType.PDF,
                pageNumber = 143,
                sectionHeading = "Prophylaxe",
                content = "Zur Dekubitusprophylaxe gehören regelmäßige Positionswechsel und eine druckverteilende Lagerung. Eine frühzeitige Hautinspektion deckt erste Rötungen auf."
            ),
            score = 0.9,
            matchedTerms = setOf("positionswechsel", "lagerung"),
            classifiedSentences = emptyList()
        )
    )

    @Test
    fun `prompt builder embeds source markers and strict grounding rule`() {
        val config = PrepGenerationConfig(
            prepItemId = 1L,
            title = "Dekubitusprophylaxe",
            categoryOrCompetenceArea = "KB I",
            keywords = listOf("Auflagedruck", "Lagerung"),
            detailDepth = DetailDepth.STANDARD,
            focusParameters = listOf("Ursachen", "Prophylaxe"),
            targetFlashcardCount = 5,
            flashcardTypes = setOf(FlashcardType.CAUSAL, FlashcardType.CAVE),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )
        val prompt = AiPromptBuilder.buildStudyAndFlashcardPrompt(config, passages)
        assertTrue(prompt.systemPrompt.contains("KEINERLEI eigenes Vorwissen"))
        assertTrue(prompt.userPrompt.contains("[BELEG-1"))
        assertTrue(prompt.userPrompt.contains("[BELEG-2"))
        assertTrue(prompt.userPrompt.contains("PflegeHeute Dekubitus"))
    }

    @Test
    fun `parseStudyAndCards parses fenced JSON and maps valid evidence indices`() {
        val rawAiJson = """
            ```json
            {
              "sections": [
                {
                  "parameterTitle": "Ursachen",
                  "bullets": [
                    { "statement": "Anhaltender Auflagedruck mindert die kapilläre Durchblutung.", "evidenceIndex": 1 },
                    { "statement": "Scherkräfte erhöhen die Gewebeschädigung.", "evidenceIndex": 1 }
                  ]
                },
                {
                  "parameterTitle": "Prophylaxe",
                  "bullets": [
                    { "statement": "Regelmäßige Positionswechsel entlasten gefährdete Hautareale.", "evidenceIndex": 2 }
                  ]
                }
              ],
              "flashcards": [
                {
                  "cardType": "CAUSAL",
                  "parameterTag": "Ursachen",
                  "frontText": "Warum begünstigt anhaltender Auflagedruck einen Dekubitus?",
                  "backText": "Er mindert die kapilläre Durchblutung im Gewebe.",
                  "evidenceIndex": 1
                },
                {
                  "cardType": "CAVE",
                  "parameterTag": "Risiken",
                  "frontText": "Was ist bezüglich Scherkräften zu beachten?",
                  "backText": "Scherkräfte verstärken die Gewebeschädigung zusätzlich.",
                  "evidenceIndex": 2
                }
              ]
            }
            ```
        """.trimIndent()

        val config = PrepGenerationConfig(
            prepItemId = 1L,
            title = "Dekubitusprophylaxe",
            categoryOrCompetenceArea = "KB I",
            keywords = emptyList(),
            detailDepth = DetailDepth.STANDARD,
            focusParameters = listOf("Ursachen", "Prophylaxe"),
            targetFlashcardCount = 5,
            flashcardTypes = setOf(FlashcardType.CAUSAL, FlashcardType.CAVE),
            selectedSourceIds = emptySet(),
            customPromptNote = ""
        )

        val parsed = AiJsonResponseParser.parseStudyAndCards(rawAiJson, config, passages)
        assertNotNull(parsed)
        assertNotNull(parsed!!.studyNote)
        assertEquals(2, parsed.studyNote!!.sections.size)
        assertEquals("S. 142", parsed.studyNote!!.sections[0].bullets[0].citation.pageLabel)
        assertEquals(2, parsed.flashcards.size)
        assertEquals("PflegeHeute Dekubitus · S. 142 · Pathophysiologie", parsed.flashcards[0].citationLabel)
        assertEquals("PflegeHeute Dekubitus · S. 143 · Prophylaxe", parsed.flashcards[1].citationLabel)
    }

    @Test
    fun `parseCaseStudy maps questions and evidence citations cleanly`() {
        val operators = listOf(
            OperatorPromptSpec(
                operatorId = 1L,
                name = "Begründen",
                requirementLevel = "AFB II",
                description = "Maßnahmen fachlich herleiten",
                questionPattern = "",
                structureType = OperatorStructureType.CAUSAL_REASONING,
                defaultPoints = 6
            )
        )
        val rawCaseJson = """
            {
              "vignetteText": "Frau M., 82 Jahre, ist nach einer Schenkelhalsfraktur immobil und weist eine beginnende Druckrötung am Kreuzbein auf.",
              "isOriginalCaseFromSource": false,
              "vignetteEvidenceIndices": [1, 2],
              "questions": [
                {
                  "operatorId": 1,
                  "questionText": "Begründen Sie bei Frau M. den Einsatz regelmäßiger Positionswechsel.",
                  "modelAnswerText": "Durch regelmäßige Positionswechsel wird anhaltender Auflagedruck reduziert, sodass die kapilläre Durchblutung erhalten bleibt.",
                  "expectedKeyPoints": ["Auflagedruck reduzieren", "Kapilläre Durchblutung erhalten"],
                  "evidenceIndices": [1, 2]
                }
              ]
            }
        """.trimIndent()

        val result = AiJsonResponseParser.parseCaseStudy(rawCaseJson, "Dekubitusprophylaxe", passages, operators)
        assertNotNull(result)
        assertEquals(2, result!!.vignetteCitations.size)
        assertEquals(1, result.questions.size)
        assertEquals("Begründen", result.questions.first().operatorName)
        assertEquals(6, result.questions.first().maxPoints)
        assertEquals(2, result.questions.first().citations.size)
    }

    @Test
    fun `prompts enforce anatomy emphasis and operator case relation rules`() {
        val config = PrepGenerationConfig(
            prepItemId = 2L,
            title = "Herzinsuffizienz",
            categoryOrCompetenceArea = "Tag 1",
            keywords = listOf("Anatomie", "Physiologie"),
            detailDepth = DetailDepth.EXAM_DEEP,
            focusParameters = listOf("Anatomie & Physiologie", "Symptome & Beobachtung"),
            targetFlashcardCount = 8,
            flashcardTypes = setOf(FlashcardType.DEFINITION),
            selectedSourceIds = emptySet(),
            customPromptNote = "Krankheitsbild"
        )
        val studyPrompt = AiPromptBuilder.buildStudyAndFlashcardPrompt(config, passages)
        assertTrue(studyPrompt.userPrompt.contains("Anatomie & Physiologie"))
        assertTrue(studyPrompt.userPrompt.contains("WICHTIGER HINWEIS ZU KRANKHEITSBILDERN"))

        val ops = listOf(
            OperatorPromptSpec(
                operatorId = 10L,
                name = "Definieren",
                requirementLevel = "Taxonomie WV",
                description = "Allgemeingültig definieren",
                questionPattern = "",
                structureType = OperatorStructureType.DEFINITION,
                defaultPoints = 2,
                gradingRubric = "Pro Definition 2 Punkte.",
                caseRelationRule = CaseRelationRule.NO_CASE_RELATION
            )
        )
        val casePrompt = AiPromptBuilder.buildCaseStudyPrompt("Herzinsuffizienz", passages, ops)
        assertTrue(casePrompt.userPrompt.contains("KEIN Fallbezug"))
        assertTrue(casePrompt.userPrompt.contains("Pro Definition 2 Punkte."))
    }
}
