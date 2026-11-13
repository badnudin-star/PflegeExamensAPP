package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ClassifiedSentence
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SentenceRole
import de.pflegeexamen.app.domain.model.SourceCitation
import de.pflegeexamen.app.domain.model.StudyNotePoint
import de.pflegeexamen.app.domain.model.StudyNoteSection
import java.util.Locale

data class GeneratedStudyNote(
    val title: String,
    val sections: List<StudyNoteSection>,
    val summaryMarkdown: String,
    val sourceSummary: String
)

/**
 * Erstellt strukturierte Lernzettel strikt aus den gefundenen Quellenpassagen
 * gemäß den vom Nutzer in der Vorbereitungsliste definierten Parametern.
 */
object StudyNoteGenerator {

    fun generate(
        config: PrepGenerationConfig,
        passages: List<RetrievedPassage>
    ): GeneratedStudyNote? {
        if (passages.isEmpty()) return null

        val maxPerSection = config.detailDepth.maxPointsPerSection
        val candidateSentences = flattenCandidates(passages)
        if (candidateSentences.isEmpty()) return null

        val usedSentenceKeys = HashSet<String>()
        val builtSections = mutableListOf<StudyNoteSection>()

        val requestedParameters = if (config.focusParameters.isNotEmpty()) {
            config.focusParameters
        } else {
            listOf(
                "Kernbegriffe & Definitionen",
                "Fakten, Ursachen & Beobachtung",
                "Pflegemaßnahmen & Begründungen",
                "Wichtige Hinweise (Merke / Cave)"
            )
        }

        val queryTerms = SourceRetriever.buildQueryTerms(config.title, config.keywords)

        for (param in requestedParameters) {
            val matchedPoints = selectSentencesForParameter(
                parameterName = param,
                candidates = candidateSentences,
                queryTerms = queryTerms,
                usedKeys = usedSentenceKeys,
                limit = maxPerSection
            )
            if (matchedPoints.isNotEmpty()) {
                builtSections.add(
                    StudyNoteSection(
                        parameterTitle = param,
                        bullets = matchedPoints
                    )
                )
            }
        }

        // Ergänze weitere relevante Kernpunkte aus den Quellen, falls Parameter sehr eng waren
        val remaining = candidateSentences
            .filter { keyOf(it.sentence.text) !in usedSentenceKeys }
            .sortedByDescending { scoreCandidate(it, queryTerms, emptySet(), emptyList()) }
            .take((maxPerSection / 2).coerceAtLeast(3))

        if (remaining.isNotEmpty()) {
            val extraPoints = remaining.map { c ->
                usedSentenceKeys.add(keyOf(c.sentence.text))
                toStudyNotePoint(c)
            }
            builtSections.add(
                StudyNoteSection(
                    parameterTitle = if (builtSections.isEmpty()) "Zentrale Quellenaussagen" else "Weitere belegte Aspekte",
                    bullets = extraPoints
                )
            )
        }

        if (builtSections.isEmpty()) return null

        val usedSources = passages
            .map { it.chunk }
            .distinctBy { it.sourceId }
            .joinToString("; ") { chunk ->
                val pages = passages
                    .filter { it.chunk.sourceId == chunk.sourceId && it.chunk.pageNumber != null }
                    .mapNotNull { it.chunk.pageNumber }
                    .distinct()
                    .sorted()
                if (pages.isNotEmpty()) {
                    "${chunk.sourceTitle} (S. ${pages.joinToString(", ")})"
                } else {
                    chunk.sourceTitle
                }
            }

        val markdown = buildMarkdown(config, builtSections, usedSources)
        val noteTitle = if (config.categoryOrCompetenceArea.isNotBlank()) {
            "${config.title} (${config.categoryOrCompetenceArea})"
        } else {
            config.title
        }

        return GeneratedStudyNote(
            title = noteTitle,
            sections = builtSections,
            summaryMarkdown = markdown,
            sourceSummary = usedSources
        )
    }

    private fun selectSentencesForParameter(
        parameterName: String,
        candidates: List<SentenceCandidate>,
        queryTerms: List<String>,
        usedKeys: MutableSet<String>,
        limit: Int
    ): List<StudyNotePoint> {
        val targetRoles = SourceRetriever.mapParametersToRoles(listOf(parameterName))
        val paramTokens = GermanTextAnalyzer.tokenizeKeywords(parameterName, includeStems = false)

        val ranked = candidates
            .filter { keyOf(it.sentence.text) !in usedKeys }
            .mapNotNull { cand ->
                val score = scoreCandidate(cand, queryTerms, targetRoles, paramTokens)
                if (score <= 0.0) null else cand to score
            }
            .sortedByDescending { it.second }

        val selected = mutableListOf<StudyNotePoint>()
        for ((cand, _) in ranked) {
            if (selected.size >= limit) break
            val duplicate = selected.any {
                GermanTextAnalyzer.jaccardSimilarity(it.statement, cand.sentence.text) >= 0.75
            }
            if (duplicate) continue
            usedKeys.add(keyOf(cand.sentence.text))
            selected.add(toStudyNotePoint(cand))
        }
        return selected
    }

    private fun scoreCandidate(
        cand: SentenceCandidate,
        queryTerms: List<String>,
        targetRoles: Set<SentenceRole>,
        paramTokens: List<String>
    ): Double {
        val normText = GermanTextAnalyzer.normalizeForMatching(cand.sentence.text)
        val tokens = GermanTextAnalyzer.tokenizeKeywords(cand.sentence.text).toSet()

        var score = cand.passageScore * 0.25

        // Role match
        val roleMatches = cand.sentence.roles.count { it in targetRoles }
        if (targetRoles.isNotEmpty() && roleMatches == 0) {
            // Check direct parameter keyword match when parameter is custom
            val pHit = paramTokens.count { GermanTextAnalyzer.matchesTerm(it, tokens, normText) }
            if (pHit == 0) return 0.0
            score += pHit * 1.8
        } else {
            score += roleMatches * 2.2
        }

        // Query term relevance inside the specific sentence
        val qHits = queryTerms.count { GermanTextAnalyzer.matchesTerm(it, tokens, normText) }
        score += qHits * 1.1

        // Parameter text tokens bonus
        val pHits = paramTokens.count { GermanTextAnalyzer.matchesTerm(it, tokens, normText) }
        score += pHits * 1.4

        // Prefer clear informative statements
        if (cand.sentence.text.length in 35..340) score += 0.4
        return score
    }

    private fun flattenCandidates(passages: List<RetrievedPassage>): List<SentenceCandidate> {
        val out = mutableListOf<SentenceCandidate>()
        for (p in passages) {
            for (s in p.classifiedSentences) {
                val cleaned = s.text.trim()
                if (cleaned.length < 18) continue
                out.add(SentenceCandidate(p, s))
            }
        }
        return out
    }

    private fun toStudyNotePoint(cand: SentenceCandidate): StudyNotePoint {
        val chunk = cand.passage.chunk
        val statement = GermanTextAnalyzer.stripBulletPrefix(cand.sentence.text)
        return StudyNotePoint(
            statement = statement,
            citation = SourceCitation(
                sourceId = chunk.sourceId,
                sourceTitle = chunk.sourceTitle,
                pageLabel = chunk.pageLabel(),
                sectionHeading = chunk.sectionHeading,
                excerpt = chunk.content.take(420)
            )
        )
    }

    private fun buildMarkdown(
        config: PrepGenerationConfig,
        sections: List<StudyNoteSection>,
        usedSources: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# ${config.title}")
        if (config.categoryOrCompetenceArea.isNotBlank()) {
            sb.appendLine("**Bereich:** ${config.categoryOrCompetenceArea}")
        }
        sb.appendLine("**Detailtiefe:** ${config.detailDepth.displayLabel}")
        sb.appendLine("**Ausschließlich verwendete Quellen:** $usedSources")
        sb.appendLine()
        for (section in sections) {
            sb.appendLine("## ${section.parameterTitle}")
            for (bullet in section.bullets) {
                val ref = bullet.citation.shortLabel()
                sb.appendLine("- ${bullet.statement} *[$ref]*")
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    private fun keyOf(text: String): String =
        GermanTextAnalyzer.normalizeForMatching(GermanTextAnalyzer.stripBulletPrefix(text))

    private data class SentenceCandidate(
        val passage: RetrievedPassage,
        val sentence: ClassifiedSentence
    ) {
        val passageScore: Double get() = passage.score
    }
}
