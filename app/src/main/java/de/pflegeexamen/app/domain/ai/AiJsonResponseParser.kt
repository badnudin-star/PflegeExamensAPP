package de.pflegeexamen.app.domain.ai

import de.pflegeexamen.app.domain.engine.GeneratedCaseQuestion
import de.pflegeexamen.app.domain.engine.GeneratedCaseStudy
import de.pflegeexamen.app.domain.engine.GeneratedFlashcard
import de.pflegeexamen.app.domain.engine.GeneratedStudyNote
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SourceCitation
import de.pflegeexamen.app.domain.model.StudyNotePoint
import de.pflegeexamen.app.domain.model.StudyNoteSection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AiStudyBulletDto(
    val statement: String = "",
    val evidenceIndex: Int = 1
)

@Serializable
private data class AiStudySectionDto(
    val parameterTitle: String = "",
    val bullets: List<AiStudyBulletDto> = emptyList()
)

@Serializable
private data class AiFlashcardDto(
    val cardType: String = "DEFINITION",
    val parameterTag: String = "",
    val frontText: String = "",
    val backText: String = "",
    val evidenceIndex: Int = 1
)

@Serializable
private data class AiStudyAndCardsPayload(
    val sections: List<AiStudySectionDto> = emptyList(),
    val flashcards: List<AiFlashcardDto> = emptyList()
)

@Serializable
private data class AiCaseQuestionDto(
    val operatorId: Long = 0,
    val questionText: String = "",
    val modelAnswerText: String = "",
    val expectedKeyPoints: List<String> = emptyList(),
    val evidenceIndices: List<Int> = emptyList()
)

@Serializable
private data class AiCaseStudyPayload(
    val vignetteText: String = "",
    val isOriginalCaseFromSource: Boolean = false,
    val vignetteEvidenceIndices: List<Int> = emptyList(),
    val questions: List<AiCaseQuestionDto> = emptyList()
)

data class AiParsedStudyAndCards(
    val studyNote: GeneratedStudyNote?,
    val flashcards: List<GeneratedFlashcard>
)

/**
 * Parst strukturierte JSON-Antworten von KI-Modellen (Groq / Gemini / Ollama) robust
 * und ordnet jede Aussage wieder den realen SourceCitation-Objekten der lokalen Quellen zu.
 */
object AiJsonResponseParser {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun extractJsonObject(rawResponse: String): String? {
        val cleaned = rawResponse
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    fun parseStudyAndCards(
        rawResponse: String,
        config: PrepGenerationConfig,
        passages: List<RetrievedPassage>
    ): AiParsedStudyAndCards? {
        val jsonStr = extractJsonObject(rawResponse) ?: return null
        val payload = runCatching {
            lenientJson.decodeFromString<AiStudyAndCardsPayload>(jsonStr)
        }.getOrNull() ?: return null

        val maxPassages = passages.take(12)

        val sections = payload.sections.mapNotNull { sec ->
            val bullets = sec.bullets.mapNotNull { b ->
                val text = b.statement.trim()
                if (text.length < 8) return@mapNotNull null
                val citation = citationForIndex(b.evidenceIndex, maxPassages)
                StudyNotePoint(statement = text, citation = citation)
            }
            if (bullets.isEmpty() || sec.parameterTitle.isBlank()) null
            else StudyNoteSection(parameterTitle = sec.parameterTitle.trim(), bullets = bullets)
        }

        val usedSources = if (maxPassages.isEmpty()) "KI-generiert – kein Quellenbeleg; fachlich überprüfen" else maxPassages
            .map { it.chunk }
            .distinctBy { it.sourceId }
            .joinToString("; ") { c ->
                val pages = maxPassages
                    .filter { it.chunk.sourceId == c.sourceId && it.chunk.pageNumber != null }
                    .mapNotNull { it.chunk.pageNumber }
                    .distinct()
                    .sorted()
                if (pages.isNotEmpty()) "${c.sourceTitle} (S. ${pages.joinToString(", ")})" else c.sourceTitle
            }

        val note = if (sections.isNotEmpty()) {
            val noteTitle = if (config.categoryOrCompetenceArea.isNotBlank()) {
                "${config.title} (${config.categoryOrCompetenceArea})"
            } else {
                config.title
            }
            val md = buildString {
                appendLine("# ${config.title}")
                if (config.categoryOrCompetenceArea.isNotBlank()) {
                    appendLine("**Kategorie / Bereich:** ${config.categoryOrCompetenceArea}")
                }
                appendLine("**Detailtiefe:** ${config.detailDepth.displayLabel}")
                appendLine("**Grundlage:** $usedSources")
                appendLine()
                for (sec in sections) {
                    appendLine("## ${sec.parameterTitle}")
                    for (b in sec.bullets) {
                        appendLine("- ${b.statement} *[${b.citation.shortLabel()}]*")
                    }
                    appendLine()
                }
            }.trim()

            GeneratedStudyNote(
                title = noteTitle,
                sections = sections,
                summaryMarkdown = md,
                sourceSummary = usedSources
            )
        } else null

        val cards = payload.flashcards.mapNotNull { dto ->
            val front = dto.frontText.trim()
            val back = dto.backText.trim()
            if (front.length < 8 || back.length < 4) return@mapNotNull null
            val type = runCatching { FlashcardType.valueOf(dto.cardType.trim().uppercase()) }
                .getOrDefault(FlashcardType.DEFINITION)
            val citation = citationForIndex(dto.evidenceIndex, maxPassages)
            GeneratedFlashcard(
                cardType = type,
                frontText = front,
                backText = "$back\n\nGrundlage: ${citation.shortLabel()}",
                citationLabel = citation.shortLabel(),
                sourceExcerpt = citation.excerpt,
                parameterTag = dto.parameterTag.ifBlank { type.displayLabel }
            )
        }.take(config.targetFlashcardCount.coerceIn(1, 50))

        if (note == null && cards.isEmpty()) return null
        return AiParsedStudyAndCards(studyNote = note, flashcards = cards)
    }

    fun parseCaseStudy(
        rawResponse: String,
        topicTitle: String,
        passages: List<RetrievedPassage>,
        operators: List<OperatorPromptSpec>
    ): GeneratedCaseStudy? {
        val jsonStr = extractJsonObject(rawResponse) ?: return null
        val payload = runCatching {
            lenientJson.decodeFromString<AiCaseStudyPayload>(jsonStr)
        }.getOrNull() ?: return null

        val maxPassages = passages.take(12)
        if (payload.vignetteText.isBlank() || payload.questions.isEmpty()) {
            return null
        }

        val vignetteCitations = payload.vignetteEvidenceIndices
            .ifEmpty { listOf(1) }
            .map { citationForIndex(it, maxPassages) }
            .distinctBy { "${it.sourceId}_${it.pageLabel}_${it.excerpt.take(35)}" }

        val sourceBasisLabel = if (maxPassages.isEmpty()) "KI-generiert – kein Quellenbeleg; fachlich überprüfen" else maxPassages
            .map { it.chunk }
            .distinctBy { it.sourceId }
            .joinToString("; ") { c ->
                val pages = maxPassages
                    .filter { it.chunk.sourceId == c.sourceId && it.chunk.pageNumber != null }
                    .mapNotNull { it.chunk.pageNumber }
                    .distinct()
                    .sorted()
                if (pages.isNotEmpty()) "${c.sourceTitle} (S. ${pages.joinToString(", ")})" else c.sourceTitle
            }

        val questions = payload.questions.mapIndexedNotNull { idx, qDto ->
            if (qDto.questionText.isBlank() || qDto.modelAnswerText.isBlank()) return@mapIndexedNotNull null
            val op = operators.firstOrNull { it.operatorId == qDto.operatorId }
                ?: operators.getOrNull(idx)
                ?: operators.first()

            val citations = qDto.evidenceIndices
                .ifEmpty { listOf(1) }
                .map { citationForIndex(it, maxPassages) }
                .distinctBy { "${it.sourceId}_${it.pageLabel}_${it.excerpt.take(35)}" }

            val keyPoints = qDto.expectedKeyPoints
                .map { it.trim() }
                .filter { it.length >= 4 }
                .take(6)

            val fullHint = buildString {
                append(op.description)
                if (op.expectationHorizon.isNotBlank()) {
                    append("\nErwartungshorizont: ${op.expectationHorizon}")
                }
            }.trim()

            GeneratedCaseQuestion(
                orderIndex = idx + 1,
                operatorId = op.operatorId,
                operatorName = op.name,
                operatorLevel = op.requirementLevel,
                operatorHint = fullHint,
                operatorGradingRubric = op.gradingRubric,
                operatorCommentNotes = op.commentNotes,
                operatorCaseRelationRule = op.caseRelationRule.displayLabel,
                questionText = qDto.questionText.trim(),
                modelAnswerText = qDto.modelAnswerText.trim(),
                expectedKeyPoints = keyPoints,
                citations = citations,
                maxPoints = op.defaultPoints.coerceAtLeast(1)
            )
        }

        if (questions.isEmpty()) return null

        return GeneratedCaseStudy(
            title = "Fallbeispiel: $topicTitle",
            vignetteText = payload.vignetteText.trim(),
            isExtractedOriginalCase = payload.isOriginalCaseFromSource,
            vignetteCitations = vignetteCitations,
            sourceBasisLabel = sourceBasisLabel,
            questions = questions,
            skippedOperatorsInfo = emptyList()
        )
    }

    private fun citationForIndex(oneBasedIndex: Int, passages: List<RetrievedPassage>): SourceCitation {
        if (passages.isEmpty()) {
            // Smartphone-KI ohne Quellen: Kennzeichnung statt eines vorgetäuschten Quellenbelegs.
            return SourceCitation(
                sourceId = 0L,
                sourceTitle = "KI-generiert – kein Quellenbeleg",
                pageLabel = "On-Device",
                sectionHeading = "Fachlich überprüfen",
                excerpt = "Erzeugt von der Smartphone-KI direkt auf dem Gerät – ohne eigene Quellen."
            )
        }
        val idx = (oneBasedIndex - 1).coerceIn(0, passages.lastIndex)
        val c = passages[idx].chunk
        return SourceCitation(
            sourceId = c.sourceId,
            sourceTitle = c.sourceTitle,
            pageLabel = c.pageLabel(),
            sectionHeading = c.sectionHeading,
            excerpt = c.content.take(380)
        )
    }
}
