package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.ClassifiedSentence
import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SentenceRole
import de.pflegeexamen.app.domain.model.SourceCitation
import java.util.Locale

data class GeneratedCaseQuestion(
    val orderIndex: Int,
    val operatorId: Long,
    val operatorName: String,
    val operatorLevel: String,
    val operatorHint: String,
    val operatorGradingRubric: String = "",
    val operatorCommentNotes: String = "",
    val operatorCaseRelationRule: String = "",
    val questionText: String,
    val modelAnswerText: String,
    val expectedKeyPoints: List<String>,
    val citations: List<SourceCitation>,
    val maxPoints: Int
)

data class GeneratedCaseStudy(
    val title: String,
    val vignetteText: String,
    val isExtractedOriginalCase: Boolean,
    val vignetteCitations: List<SourceCitation>,
    val sourceBasisLabel: String,
    val questions: List<GeneratedCaseQuestion>,
    val skippedOperatorsInfo: List<String>
)

/**
 * Erzeugt schriftliche Examens-Fallbeispiele und dazugehörige operatorgestützte Fragen
 * ausschließlich aus den vom Nutzer hinterlegten Quellen und der Landes-AG/eigenen Operatorenliste.
 * Es werden keinerlei externe Fakten hinzugefügt.
 */
object CaseStudyGenerator {

    private val germanLocale = Locale.GERMAN

    fun generate(
        topicTitle: String,
        passages: List<RetrievedPassage>,
        selectedOperators: List<OperatorPromptSpec>
    ): GeneratedCaseStudy? {
        if (passages.isEmpty() || selectedOperators.isEmpty()) return null

        val (vignetteText, isOriginalCase, vignetteCitations) = buildVignette(topicTitle, passages)
        val sourceBasisLabel = passages
            .map { it.chunk }
            .distinctBy { it.sourceId }
            .joinToString("; ") { c ->
                val pages = passages
                    .filter { it.chunk.sourceId == c.sourceId && it.chunk.pageNumber != null }
                    .mapNotNull { it.chunk.pageNumber }
                    .distinct()
                    .sorted()
                if (pages.isNotEmpty()) "${c.sourceTitle} (S. ${pages.joinToString(", ")})" else c.sourceTitle
            }

        val usedSentenceKeys = HashSet<String>()
        val questions = mutableListOf<GeneratedCaseQuestion>()
        val skippedInfo = mutableListOf<String>()

        selectedOperators.forEachIndexed { idx, op ->
            val generated = buildQuestionForOperator(
                orderIndex = idx + 1,
                topicTitle = topicTitle,
                operator = op,
                passages = passages,
                usedSentenceKeys = usedSentenceKeys
            )
            if (generated != null) {
                questions.add(generated)
            } else {
                skippedInfo.add(
                    "Für den Operator „${op.name}“ fanden sich in den gewählten Quellen keine weiteren eindeutigen Belegstellen."
                )
            }
        }

        if (questions.isEmpty()) return null

        return GeneratedCaseStudy(
            title = "Fallbeispiel: $topicTitle",
            vignetteText = vignetteText,
            isExtractedOriginalCase = isOriginalCase,
            vignetteCitations = vignetteCitations,
            sourceBasisLabel = sourceBasisLabel,
            questions = questions,
            skippedOperatorsInfo = skippedInfo
        )
    }

    private fun buildVignette(
        topicTitle: String,
        passages: List<RetrievedPassage>
    ): Triple<String, Boolean, List<SourceCitation>> {
        val caseChunks = passages.filter { p ->
            p.classifiedSentences.count { SentenceRole.CASE_CONTEXT in it.roles } >= 2 ||
                p.chunk.sectionHeading.lowercase(germanLocale).contains("fall") ||
                p.chunk.sectionHeading.lowercase(germanLocale).contains("situation")
        }

        if (caseChunks.isNotEmpty()) {
            val chosen = caseChunks.take(2)
            val vignetteParts = chosen.map { p ->
                val label = p.chunk.pageLabel().let { if (it.isNotBlank()) " [$it]" else "" }
                "${p.chunk.content}$label"
            }
            val citations = chosen.map { toCitation(it) }
            val text = buildString {
                appendLine("Ausgangssituation (aus Ihrer hinterlegten Quelle übernommen):")
                appendLine()
                append(vignetteParts.joinToString("\n\n"))
            }
            return Triple(text.trim(), true, citations)
        }

        val citations = mutableListOf<SourceCitation>()
        val defSentences = collectRoleSentences(passages, setOf(SentenceRole.DEFINITION), 2)
        val riskOrCauseSentences = collectRoleSentences(passages, setOf(SentenceRole.CAUSE_RISK), 2)
        val symptomSentences = collectRoleSentences(passages, setOf(SentenceRole.SYMPTOM_ASSESSMENT), 3)
        val contextSentences = collectRoleSentences(
            passages,
            setOf(SentenceRole.INTERVENTION_MEASURE, SentenceRole.LEGAL_ETHICS, SentenceRole.CAVE_WARNING),
            2
        )

        val sb = StringBuilder()
        sb.appendLine("Prüfungssituation zum Themenfeld „$topicTitle“")
        sb.appendLine("(Ausschließlich aus Ihren hinterlegten Quellen zusammengestellt – keine externen Fakten ergänzt):")
        sb.appendLine()

        if (defSentences.isNotEmpty()) {
            sb.appendLine("Fachlicher Rahmen laut Quelle:")
            defSentences.forEach { (p, s) ->
                val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
                sb.appendLine("• $clean [${p.chunk.sourceTitle}${p.chunk.pageLabel().let { if (it.isNotBlank()) ", $it" else "" }}]")
                citations.add(toCitation(p, clean))
            }
            sb.appendLine()
        }

        if (riskOrCauseSentences.isNotEmpty()) {
            sb.appendLine("Auslöser / Risikofaktoren im vorliegenden Kontext:")
            riskOrCauseSentences.forEach { (p, s) ->
                val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
                sb.appendLine("• $clean [${p.chunk.sourceTitle}${p.chunk.pageLabel().let { if (it.isNotBlank()) ", $it" else "" }}]")
                citations.add(toCitation(p, clean))
            }
            sb.appendLine()
        }

        if (symptomSentences.isNotEmpty()) {
            sb.appendLine("Beschriebene Beobachtungen / Befunde / Werte:")
            symptomSentences.forEach { (p, s) ->
                val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
                sb.appendLine("• $clean [${p.chunk.sourceTitle}${p.chunk.pageLabel().let { if (it.isNotBlank()) ", $it" else "" }}]")
                citations.add(toCitation(p, clean))
            }
            sb.appendLine()
        }

        if (contextSentences.isNotEmpty()) {
            sb.appendLine("Pflegerische / rechtliche Schwerpunkte laut Quelle:")
            contextSentences.forEach { (p, s) ->
                val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
                sb.appendLine("• $clean [${p.chunk.sourceTitle}${p.chunk.pageLabel().let { if (it.isNotBlank()) ", $it" else "" }}]")
                citations.add(toCitation(p, clean))
            }
        }

        if (citations.isEmpty()) {
            val fallback = passages.first()
            val clean = fallback.chunk.content.take(450)
            sb.append(clean)
            citations.add(toCitation(fallback, clean))
        }

        return Triple(sb.toString().trim(), false, citations.distinctBy { "${it.sourceId}_${it.pageLabel}_${it.excerpt.take(40)}" })
    }

    private fun buildQuestionForOperator(
        orderIndex: Int,
        topicTitle: String,
        operator: OperatorPromptSpec,
        passages: List<RetrievedPassage>,
        usedSentenceKeys: MutableSet<String>
    ): GeneratedCaseQuestion? {
        val preferredRoles = rolesForOperator(operator.structureType)

        val allCandidates = passages.flatMap { p ->
            p.classifiedSentences.map { s -> p to s }
        }.filter { (_, s) ->
            GermanTextAnalyzer.stripBulletPrefix(s.text).length >= 16
        }

        val freshCandidates = allCandidates.filter { (_, s) ->
            normalizeKey(s.text) !in usedSentenceKeys
        }
        val pool = if (freshCandidates.size >= 2) freshCandidates else allCandidates
        if (pool.isEmpty()) return null

        val ranked = pool.sortedByDescending { (p, s) ->
            var score = p.score * 0.25
            val roleHits = s.roles.count { it in preferredRoles }
            score += roleHits * 3.0
            if (operator.structureType == OperatorStructureType.FACTS_LIST && s.isListItem) {
                score += 2.2
            }
            if (operator.structureType == OperatorStructureType.CAUSAL_REASONING &&
                (SentenceRole.REASON_GOAL in s.roles || SentenceRole.CAUSE_RISK in s.roles)
            ) {
                score += 2.4
            }
            // Bei Operatoren ohne Fallbezug (Definieren, Erklären, Begründen) allgemeine Fachtexte gegenüber Fallzeilen bevorzugen
            if (operator.caseRelationRule == CaseRelationRule.NO_CASE_RELATION && SentenceRole.CASE_CONTEXT in s.roles) {
                score -= 2.5
            } else if (SentenceRole.CASE_CONTEXT in s.roles && preferredRoles.isNotEmpty() && roleHits == 0) {
                score -= 1.0
            }
            score
        }

        val maxAnswerStatements = when (operator.structureType) {
            OperatorStructureType.FACTS_LIST -> 5
            OperatorStructureType.PROCESS_PLANNING -> 5
            OperatorStructureType.COMPARISON -> 4
            OperatorStructureType.EVALUATION -> 4
            else -> 3
        }

        val chosen = mutableListOf<Pair<RetrievedPassage, ClassifiedSentence>>()
        for (pair in ranked) {
            if (chosen.size >= maxAnswerStatements) break
            val clean = GermanTextAnalyzer.stripBulletPrefix(pair.second.text)
            val dup = chosen.any {
                GermanTextAnalyzer.jaccardSimilarity(
                    GermanTextAnalyzer.stripBulletPrefix(it.second.text),
                    clean
                ) >= 0.72
            }
            if (!dup) {
                chosen.add(pair)
                usedSentenceKeys.add(normalizeKey(pair.second.text))
            }
        }

        if (chosen.isEmpty()) return null

        val primaryHeading = chosen.first().first.chunk.sectionHeading
            .takeIf { it.isNotBlank() && it != "Allgemein" } ?: topicTitle

        val questionText = formatOperatorQuestion(
            operator = operator,
            topicTitle = topicTitle,
            aspectHeading = primaryHeading,
            chosenStatementsCount = chosen.size
        )

        val modelAnswer = formatModelAnswerByOperator(operator, topicTitle, chosen)
        val keyPoints = extractExpectedKeyPoints(chosen.map { it.second.text })
        val citations = chosen.map { (p, s) ->
            toCitation(p, GermanTextAnalyzer.stripBulletPrefix(s.text))
        }.distinctBy { "${it.sourceId}_${it.pageLabel}_${it.excerpt.take(40)}" }

        val combinedHint = buildString {
            append(operator.description)
            if (operator.expectationHorizon.isNotBlank()) {
                append("\nErwartungshorizont: ${operator.expectationHorizon}")
            }
        }.trim()

        return GeneratedCaseQuestion(
            orderIndex = orderIndex,
            operatorId = operator.operatorId,
            operatorName = operator.name,
            operatorLevel = operator.requirementLevel,
            operatorHint = combinedHint,
            operatorGradingRubric = operator.gradingRubric,
            operatorCommentNotes = operator.commentNotes,
            operatorCaseRelationRule = operator.caseRelationRule.displayLabel,
            questionText = questionText,
            modelAnswerText = modelAnswer,
            expectedKeyPoints = keyPoints,
            citations = citations,
            maxPoints = operator.defaultPoints.coerceAtLeast(1)
        )
    }

    private fun formatModelAnswerByOperator(
        operator: OperatorPromptSpec,
        topicTitle: String,
        chosen: List<Pair<RetrievedPassage, ClassifiedSentence>>
    ): String {
        val opLower = operator.name.lowercase(germanLocale)
        val bulletLines = chosen.mapIndexed { i, (p, s) ->
            val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
            val ref = toCitation(p, clean).shortLabel()
            "${i + 1}. $clean [$ref]"
        }

        return when {
            opLower.contains("pflegeplan") -> buildString {
                appendLine("Quellenbasierte Musterlösung (${operator.name} – P-E-S-R | SMART | W-Fragen):")
                appendLine("• Problem / Ressource (P-E-S-R): Pflegeproblem mit Ursache (Etiology), Symptom und vorhandener Ressource aus der Situation ableiten.")
                appendLine("• Ziel (SMART): Spezifisches, messbares, attraktives, realistisches und terminiertes Pflegeziel formulieren.")
                appendLine("• Maßnahmen (Wer macht was, wann, wie oft, womit, wo...):")
                bulletLines.forEach { appendLine(it) }
            }.trim()

            opLower.contains("pflegemaßnahme") -> buildString {
                appendLine("Quellenbasierte Musterlösung (${operator.name} – Maßnahme + Pflichtbegründung im Fallbezug):")
                bulletLines.forEach { appendLine(it) }
                append("Hinweis zur Bepunktung: Konkrete Maßnahme mit Person im Fall + „..., um ...“ Begründung = volle Punktzahl.")
            }.trim()

            operator.requirementLevel.contains("ASB", ignoreCase = true) &&
                (operator.structureType == OperatorStructureType.EVALUATION ||
                    operator.structureType == OperatorStructureType.COMPARISON) -> buildString {
                appendLine("Quellenbasierte Musterlösung (${operator.name} – Struktur Einleitung / Hauptteil / Schluss):")
                appendLine("Einleitungssatz: Im Folgenden analysiere/beurteile/diskutiere ich die Fragestellung zu „$topicTitle“.")
                appendLine("Hauptteil (auf Basis der Belege):")
                bulletLines.forEach { appendLine(it) }
                append("Schlusssatz: Begründetes Fazit / fachliches Urteil passend zu den genannten Kriterien.")
            }.trim()

            else -> buildString {
                val caseNotice = if (operator.caseRelationRule == CaseRelationRule.NO_CASE_RELATION) {
                    " – allgemeingültig ohne Fallbezug"
                } else ""
                appendLine("Quellenbasierte Musterlösung (${operator.name}$caseNotice):")
                bulletLines.forEach { appendLine(it) }
            }.trim()
        }
    }

    private fun formatOperatorQuestion(
        operator: OperatorPromptSpec,
        topicTitle: String,
        aspectHeading: String,
        chosenStatementsCount: Int
    ): String {
        val opCommand = if (operator.name.contains(" Sie")) {
            operator.name.trim()
        } else {
            "${operator.name.trim()} Sie"
        }

        if (operator.questionPattern.isNotBlank()) {
            return operator.questionPattern
                .replace("{operator}", opCommand, ignoreCase = true)
                .replace("{thema}", topicTitle, ignoreCase = true)
                .replace("{aspekt}", aspectHeading, ignoreCase = true)
                .trim()
        }

        val focusPhrase = if (aspectHeading.equals(topicTitle, ignoreCase = true)) {
            "im Zusammenhang mit „$topicTitle“"
        } else {
            "im Bereich „$aspectHeading“ ($topicTitle)"
        }

        val noCaseSuffix = if (operator.caseRelationRule == CaseRelationRule.NO_CASE_RELATION) {
            " (allgemeingültig, ohne Fallbezug)"
        } else ""

        return when (operator.structureType) {
            OperatorStructureType.FACTS_LIST ->
                "$opCommand auf Basis Ihrer vorliegenden Quellen $chosenStatementsCount wesentliche Fakten, Risikofaktoren oder Anzeichen $focusPhrase$noCaseSuffix."
            OperatorStructureType.DEFINITION ->
                "$opCommand den fachlichen Sachverhalt bzw. die zentralen Begriffe $focusPhrase anhand Ihrer hinterlegten Quellen$noCaseSuffix."
            OperatorStructureType.CAUSAL_REASONING ->
                "$opCommand die fachlichen Ursachen, Wirkungszusammenhänge oder pflegerischen Begründungen $focusPhrase$noCaseSuffix."
            OperatorStructureType.PROCESS_PLANNING ->
                "$opCommand konkrete, quellenbasierte Pflegemaßnahmen, Abläufe oder eine Pflegeplanung $focusPhrase."
            OperatorStructureType.COMPARISON ->
                "$opCommand zentrale Aspekte, Pro- und Kontra-Argumente oder Kriterien $focusPhrase anhand Ihrer Quellen."
            OperatorStructureType.EVALUATION ->
                "$opCommand die beschriebene Situation sowie wichtige Kriterien, Leitlinien oder Risiken $focusPhrase fachlich auf Grundlage Ihrer Quellen."
        }
    }

    private fun extractExpectedKeyPoints(rawStatements: List<String>): List<String> {
        val keyPoints = LinkedHashSet<String>()
        for (raw in rawStatements) {
            val clean = GermanTextAnalyzer.stripBulletPrefix(raw)
            val clauses = clean.split(Regex("""[;:]"""))
                .map { it.trim() }
                .filter { it.length >= 12 }
            val primary = (clauses.firstOrNull() ?: clean).take(90)
            if (primary.length >= 10) {
                keyPoints.add(primary)
            }
            if (keyPoints.size >= 6) break
        }
        return keyPoints.toList()
    }

    private fun collectRoleSentences(
        passages: List<RetrievedPassage>,
        roles: Set<SentenceRole>,
        limit: Int
    ): List<Pair<RetrievedPassage, ClassifiedSentence>> {
        val out = mutableListOf<Pair<RetrievedPassage, ClassifiedSentence>>()
        for (p in passages) {
            for (s in p.classifiedSentences) {
                if (s.roles.any { it in roles } && s.text.length >= 22) {
                    val clean = GermanTextAnalyzer.stripBulletPrefix(s.text)
                    val dup = out.any {
                        GermanTextAnalyzer.jaccardSimilarity(
                            GermanTextAnalyzer.stripBulletPrefix(it.second.text),
                            clean
                        ) >= 0.72
                    }
                    if (!dup) {
                        out.add(p to s)
                        if (out.size >= limit) return out
                    }
                }
            }
        }
        return out
    }

    private fun rolesForOperator(type: OperatorStructureType): Set<SentenceRole> = when (type) {
        OperatorStructureType.FACTS_LIST -> setOf(
            SentenceRole.SYMPTOM_ASSESSMENT,
            SentenceRole.CAUSE_RISK,
            SentenceRole.LIST_ITEM
        )
        OperatorStructureType.DEFINITION -> setOf(
            SentenceRole.DEFINITION,
            SentenceRole.SYMPTOM_ASSESSMENT
        )
        OperatorStructureType.CAUSAL_REASONING -> setOf(
            SentenceRole.REASON_GOAL,
            SentenceRole.CAUSE_RISK
        )
        OperatorStructureType.PROCESS_PLANNING -> setOf(
            SentenceRole.INTERVENTION_MEASURE,
            SentenceRole.REASON_GOAL
        )
        OperatorStructureType.COMPARISON -> setOf(
            SentenceRole.DEFINITION,
            SentenceRole.SYMPTOM_ASSESSMENT,
            SentenceRole.CAUSE_RISK
        )
        OperatorStructureType.EVALUATION -> setOf(
            SentenceRole.CAVE_WARNING,
            SentenceRole.LEGAL_ETHICS,
            SentenceRole.REASON_GOAL
        )
    }

    private fun toCitation(passage: RetrievedPassage, customExcerpt: String? = null): SourceCitation {
        val c = passage.chunk
        return SourceCitation(
            sourceId = c.sourceId,
            sourceTitle = c.sourceTitle,
            pageLabel = c.pageLabel(),
            sectionHeading = c.sectionHeading,
            excerpt = (customExcerpt ?: c.content).take(380)
        )
    }

    private fun normalizeKey(text: String): String =
        GermanTextAnalyzer.normalizeForMatching(GermanTextAnalyzer.stripBulletPrefix(text))
}
