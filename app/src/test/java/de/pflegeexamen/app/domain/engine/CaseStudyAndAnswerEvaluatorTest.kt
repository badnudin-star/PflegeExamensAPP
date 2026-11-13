package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseStudyAndAnswerEvaluatorTest {

    private val diabetesChunks = listOf(
        ChunkInput(
            chunkId = 1,
            sourceId = 3,
            sourceTitle = "Innere Medizin Pflege.docx",
            sourceType = SourceType.DOCX,
            pageNumber = 2,
            sectionHeading = "Diabetes mellitus und Hypoglykämie",
            content = """
                Frau M., 74 Jahre alt, lebt mit Diabetes mellitus Typ 2 und zeigt nach dem Mittagessen Kaltschweißigkeit und Zittern.
                Eine Hypoglykämie liegt vor, wenn der Blutzuckerwert unter 50 mg/dl bzw. 70 mg/dl mit Symptomen abfällt.
                Typische Anzeichen einer Hypoglykämie sind Zittern, Kaltschweißigkeit, Heißhunger und Verwirrtheit.
                Bei wachen Patientinnen wird sofort schnell resorbierbare Glukose verabreicht, weil das Gehirn auf kontinuierliche Glukosezufuhr angewiesen ist.
                Cave: Bei Bewusstlosigkeit darf niemals orale Flüssigkeit verabreicht werden wegen Aspirationsgefahr.
            """.trimIndent()
        )
    )

    @Test
    fun `CaseStudyGenerator extracts source case vignette and creates operator-driven questions`() {
        val retrieval = SourceRetriever.retrieve(
            allChunks = diabetesChunks,
            titleQuery = "Hypoglykämie",
            keywords = listOf("Diabetes", "Blutzucker")
        ) as RetrievalOutcome.Found

        val operators = listOf(
            OperatorPromptSpec(
                operatorId = 1,
                name = "Nennen",
                requirementLevel = "AFB I",
                description = "Sachverhalte knapp wiedergeben",
                questionPattern = "",
                structureType = OperatorStructureType.FACTS_LIST,
                defaultPoints = 3
            ),
            OperatorPromptSpec(
                operatorId = 2,
                name = "Begründen",
                requirementLevel = "AFB II",
                description = "Ursachen und Zusammenhänge erläutern",
                questionPattern = "",
                structureType = OperatorStructureType.CAUSAL_REASONING,
                defaultPoints = 5
            )
        )

        val generated = CaseStudyGenerator.generate(
            topicTitle = "Hypoglykämie bei Diabetes mellitus",
            passages = retrieval.passages,
            selectedOperators = operators
        )

        assertNotNull(generated)
        assertTrue(generated!!.isExtractedOriginalCase)
        assertTrue(generated.vignetteText.contains("Frau M., 74 Jahre"))
        assertEquals(2, generated.questions.size)
        assertTrue(generated.questions[0].questionText.startsWith("Nennen Sie"))
        assertTrue(generated.questions[1].questionText.startsWith("Begründen Sie"))
        assertFalse(generated.questions.any { it.modelAnswerText.isBlank() })
    }

    @Test
    fun `AnswerEvaluator evaluates confirmed German answers against expected keypoints`() {
        val expected = listOf(
            "Typische Anzeichen sind Zittern, Kaltschweißigkeit und Verwirrtheit",
            "Schnell resorbierbare Glukose verabreichen, weil das Gehirn Glukose benötigt",
            "Bei Bewusstlosigkeit keine orale Flüssigkeit wegen Aspirationsgefahr"
        )

        val strongAnswer = "Man erkennt eine Unterzuckerung an Kaltschweißigkeit, Zittern und Verwirrtheit. " +
            "Bei wachen Betroffenen gibt man schnell resorbierbare Glukose für das Gehirn."
        val strongEval = AnswerEvaluator.evaluate(strongAnswer, expected)
        assertEquals(2, strongEval.matchedPoints.size)
        assertEquals(1, strongEval.missingPoints.size)

        val weakAnswer = "Ich würde Tee kochen und warten."
        val weakEval = AnswerEvaluator.evaluate(weakAnswer, expected)
        assertEquals(0, weakEval.matchedPoints.size)
    }

    @Test
    fun `CaseStudyGenerator synthesizes cited vignette from pure textbook prose`() {
        val textbookChunks = listOf(
            ChunkInput(
                chunkId = 5,
                sourceId = 9,
                sourceTitle = "Pneumonie Skript.pdf",
                sourceType = SourceType.PDF,
                pageNumber = 3,
                sectionHeading = "Pneumonieprophylaxe",
                content = """
                    Unter einer Pneumonie versteht man eine akute oder chronische Entzündung des Lungengewebes.
                    Risikofaktoren sind Immobilität, flache Atmung und geschwächte Abwehrkräfte.
                    Typische Anzeichen sind Fieber über 38,5 °C, produktiver Husten und erhöhte Atemfrequenz.
                    Pflegemaßnahme ist eine atemstimulierende Einreibung, weil dadurch die Belüftung basaler Lungenabschnitte gefördert wird.
                """.trimIndent()
            )
        )
        val retrieval = SourceRetriever.retrieve(
            allChunks = textbookChunks,
            titleQuery = "Pneumonie",
            keywords = listOf("Atemfrequenz", "Lungengewebe")
        ) as RetrievalOutcome.Found

        val op = OperatorPromptSpec(
            operatorId = 10,
            name = "Entwickeln",
            requirementLevel = "AFB III",
            description = "Pflegerische Maßnahmen planen",
            questionPattern = "{operator} für das Thema {thema} geeignete Schritte ({aspekt}).",
            structureType = OperatorStructureType.PROCESS_PLANNING,
            defaultPoints = 6
        )

        val caseStudy = CaseStudyGenerator.generate(
            topicTitle = "Pneumonieprophylaxe",
            passages = retrieval.passages,
            selectedOperators = listOf(op)
        )
        assertNotNull(caseStudy)
        assertFalse(caseStudy!!.isExtractedOriginalCase)
        assertTrue(caseStudy.vignetteText.contains("Pneumonie Skript.pdf"))
        assertTrue(caseStudy.vignetteText.contains("S. 3"))
        assertTrue(caseStudy.questions.single().questionText.contains("Entwickeln Sie"))
    }
}
