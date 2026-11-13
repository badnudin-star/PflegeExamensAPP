package de.pflegeexamen.app.domain.ai

import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievedPassage

data class PromptPair(
    val systemPrompt: String,
    val userPrompt: String
)

/**
 * Erstellt streng quellengebundene Prompts (Zero-Hallucination-Regeln) in reinem Kotlin.
 * Kann später unverändert auch über Kotlin Multiplatform (KMP) für iOS genutzt werden.
 */
object AiPromptBuilder {

    private val STRICT_SOURCE_RULE = """
Du bist ein Fachassistent für die generalistische Pflegeausbildung (schriftliche Examensprüfung in Deutschland).
OBERSTE REGEL – STRIKTE QUELLENPFLICHT:
1. Verwende AUSSCHLIESSLICH Informationen aus den nummerierten [BELEG-X]-Texten.
2. Ergänze KEINERLEI eigenes Vorwissen, keine erfundenen Laborwerte, keine nicht genannten Medikamente oder Vitalzeichen.
3. Wenn ein Parameter oder Operator in den Belegen nicht vorkommt, lasse ihn weg oder schreibe explizit „In den vorliegenden Quellen nicht erwähnt“.
4. Gib bei jedem Inhalt die passenden Belegnummern (z. B. [1, 2]) an.
5. Antworte AUSSCHLIESSLICH mit validem JSON ohne Markdown-Erklärung davor oder danach.
""".trimIndent()

    fun formatEvidenceContext(passages: List<RetrievedPassage>, maxPassages: Int = 12): String {
        return passages.take(maxPassages).mapIndexed { index, p ->
            val num = index + 1
            val page = p.chunk.pageLabel().ifBlank { "Abschnitt" }
            val heading = p.chunk.sectionHeading
            "[BELEG-$num | Quelle: ${p.chunk.sourceTitle} | $page | Thema: $heading]\n${p.chunk.content}"
        }.joinToString("\n\n---\n\n")
    }

    fun buildStudyAndFlashcardPrompt(
        config: PrepGenerationConfig,
        passages: List<RetrievedPassage>
    ): PromptPair {
        val evidence = formatEvidenceContext(passages)
        val params = config.focusParameters.ifEmpty {
            listOf("Definitionen", "Ursachen & Risiken", "Pflegemaßnahmen", "Begründungen", "Cave / Merksätze")
        }
        val anatomyHint = if (params.any { it.contains("Anatomie", ignoreCase = true) }) {
            "\nWICHTIGER HINWEIS ZU KRANKHEITSBILDERN: Berücksichtige „Anatomie & Physiologie“ als eigenen Schwerpunktabschnitt anhand der Belege (Struktur, Organfunktion, physiologische Abläufe vor Pathophysiologie)."
        } else ""
        val cardTypes = config.flashcardTypes.joinToString(", ") { it.name }

        val user = """
Erstelle aus den unten stehenden Belegen einen strukturierten Examens-Lernzettel und genau bis zu ${config.targetFlashcardCount} präzise Karteikarten.

THEMA: ${config.title}
BEREICH/KATEGORIE: ${config.categoryOrCompetenceArea.ifBlank { "Allgemein" }}
DETAILTIEFE: ${config.detailDepth.displayLabel}
GEWÜNSCHTE LERNZETTEL-PARAMETER: ${params.joinToString(" | ")}$anatomyHint
GEWÜNSCHTE KARTENTYPEN (nutze exakt diese Enum-Namen): $cardTypes
ZUSATZ-FOKUS: ${config.customPromptNote.ifBlank { "Keiner" }}

ERWARTETES JSON-SCHEMA:
{
  "sections": [
    {
      "parameterTitle": "Name des Parameters",
      "bullets": [
        { "statement": "Klare fachliche Aussage streng aus dem Beleg", "evidenceIndex": 1 }
      ]
    }
  ],
  "flashcards": [
    {
      "cardType": "DEFINITION | CAUSAL | LIST | CLOZE | CAVE",
      "parameterTag": "z.B. Anatomie & Physiologie oder Definition",
      "frontText": "Präzise Frage bzw. Lückentext mit [____]",
      "backText": "Vollständige Antwort streng aus den Belegen",
      "evidenceIndex": 1
    }
  ]
}

QUELLEN-BELEGE:
$evidence
""".trimIndent()

        return PromptPair(STRICT_SOURCE_RULE, user)
    }

    fun buildCaseStudyPrompt(
        topicTitle: String,
        passages: List<RetrievedPassage>,
        operators: List<OperatorPromptSpec>
    ): PromptPair {
        val evidence = formatEvidenceContext(passages)
        val opsText = operators.mapIndexed { idx, op ->
            buildString {
                append("${idx + 1}. operatorId=${op.operatorId}, name=\"${op.name}\", taxonomie=\"${op.requirementLevel}\", punkte=${op.defaultPoints}")
                append(", fallbezug=\"${op.caseRelationRule.promptInstruction}\"")
                if (op.expectationHorizon.isNotBlank()) {
                    append(", erwartungshorizont=\"${op.expectationHorizon}\"")
                }
                if (op.commentNotes.isNotBlank()) {
                    append(", kommentar=\"${op.commentNotes.replace("\n", " | ")}\"")
                }
                if (op.gradingRubric.isNotBlank()) {
                    append(", bepunktung=\"${op.gradingRubric}\"")
                }
                if (op.exampleQuestion.isNotBlank()) {
                    append(", beispiel=\"${op.exampleQuestion}\"")
                }
            }
        }.joinToString("\n")

        val user = """
Erstelle ein schriftliches Pflegeexamen-Fallbeispiel zum Thema „$topicTitle“ inklusive Aufgaben zu exakt diesen offiziellen Operatoren.
WICHTIG ZUR FALLGESTALTUNG:
- Wenn in den Belegen schon ein Patientenfall steht, übernimm dessen Fakten. Wenn es reine Fachtexte sind, schreibe ein praxisnahes, aber zu 100 % faktengetreues Fallbeispiel NUR mit den in den Belegen genannten Merkmalen, Symptomen, Risiken und Rahmenbedingungen (erfinde keine Werte oder Diagnosen hinzu!).
- Beachte bei JEDER Frage strikt die Fallbezugs- und Aufbau-Regel des Operators (z. B.: KEIN Fallbezug bei Definieren/Erklären/Begründen; Einleitung–Hauptteil–Schluss bei ASB-Fließtext/Stellungnahme/Diskussion/Urteil; P-E-S-R + SMART bei Pflegeplanung; Begründung Pflicht bei einzelner Pflegemaßnahme).

GEWÄHLTE OPERATOREN (Landes-AG / Jann Wegner):
$opsText

ERWARTETES JSON-SCHEMA:
{
  "vignetteText": "Zusammenhängende, praxisnahe Fallbeschreibung auf Deutsch (ausschließlich mit Fakten aus den Belegen, inkl. [BELEG-X]-Verweisen).",
  "isOriginalCaseFromSource": false,
  "vignetteEvidenceIndices": [1, 2],
  "questions": [
    {
      "operatorId": 1,
      "questionText": "Aufgabenstellung genau nach den Regeln und Beispielen des Operators",
      "modelAnswerText": "Ausformulierte Musterlösung exakt nach Erwartungshorizont (z. B. Einleitung/Hauptteil/Schluss bzw. allgemeingültig ohne Fallbezug) streng auf Basis der Belege",
      "expectedKeyPoints": ["Kernaspekt 1", "Kernaspekt 2", "Kernaspekt 3"],
      "evidenceIndices": [1]
    }
  ]
}

QUELLEN-BELEGE:
$evidence
""".trimIndent()

        return PromptPair(STRICT_SOURCE_RULE, user)
    }

    fun buildAnswerFeedbackPrompt(
        questionText: String,
        operatorName: String,
        modelAnswerText: String,
        userAnswerText: String,
        gradingRubric: String = "",
        commentNotes: String = ""
    ): PromptPair {
        val system = """
Du bist ein wohlwollender, aber fachlich genauer Pflege-Prüfer für das schriftliche Examen in Deutschland (Landes-AG / Jann Wegner).
Bewerte die bestätigte Antwort des Prüflings AUSSCHLIESSLICH anhand der quellenbasierten Musterlösung sowie der Operator-Regeln (Form/Struktur, Fallbezug, Begründungspflicht, Bepunktung).
Erkenne fachliche Synonyme fair an, aber erfinde keine neuen Fakten außerhalb der Musterlösung.
Antworte auf Deutsch in maximal 4–6 prägnanten Sätzen (inhaltliche Stärken + konkrete fehlende Punkte/Formregeln).
""".trimIndent()

        val rubricBlock = buildString {
            if (gradingRubric.isNotBlank()) appendLine("BEPUNKTUNGSSCHEMA: $gradingRubric")
            if (commentNotes.isNotBlank()) appendLine("OPERATOR-KOMMENTAR (K): $commentNotes")
        }.trim()

        val user = """
AUFGABE (Operator: $operatorName):
$questionText

${if (rubricBlock.isNotBlank()) "$rubricBlock\n\n" else ""}QUELLENBASIERTE MUSTERLÖSUNG:
$modelAnswerText

BESTÄTIGTE ANTWORT DES PRÜFLINGS:
$userAnswerText
""".trimIndent()

        return PromptPair(system, user)
    }
}
