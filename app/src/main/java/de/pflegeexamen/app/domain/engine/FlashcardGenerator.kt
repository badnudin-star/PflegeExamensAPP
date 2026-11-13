package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ClassifiedSentence
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SentenceRole
import de.pflegeexamen.app.domain.model.SourceCitation
import java.util.Locale

data class GeneratedFlashcard(
    val cardType: FlashcardType,
    val frontText: String,
    val backText: String,
    val citationLabel: String,
    val sourceExcerpt: String,
    val parameterTag: String
)

/**
 * Erstellt automatisiert deduplizierte Karteikarten (Definition, Kausalität/Begründung,
 * Aufzählung/Schritte, Lückentext/Cloze, Cave/Merksatz) ausschließlich auf Basis
 * der vom Nutzer hinterlegten Quellen.
 */
object FlashcardGenerator {

    private val germanLocale = Locale.GERMAN

    fun generate(
        config: PrepGenerationConfig,
        passages: List<RetrievedPassage>
    ): List<GeneratedFlashcard> {
        if (passages.isEmpty()) return emptyList()

        val enabledTypes = config.flashcardTypes.ifEmpty { FlashcardType.entries.toSet() }
        val targetCount = config.targetFlashcardCount.coerceIn(1, 50)
        val candidates = mutableListOf<GeneratedFlashcard>()

        // 1. Definition-Karten
        if (FlashcardType.DEFINITION in enabledTypes) {
            for (p in passages) {
                for (s in p.classifiedSentences) {
                    if (SentenceRole.DEFINITION in s.roles) {
                        buildDefinitionCard(config.title, p, s)?.let { candidates.add(it) }
                    }
                }
            }
        }

        // 2. Aufzählungs-/Kriterien-Karten
        if (FlashcardType.LIST in enabledTypes) {
            for (p in passages) {
                buildListCard(config.title, p)?.let { candidates.add(it) }
            }
        }

        // 3. Cave-/Merksatz-Karten (vor Kausal/Cloze, damit Warnhinweise nicht verloren gehen)
        if (FlashcardType.CAVE in enabledTypes) {
            for (p in passages) {
                for (s in p.classifiedSentences) {
                    if (SentenceRole.CAVE_WARNING in s.roles) {
                        buildCaveCard(config.title, p, s)?.let { candidates.add(it) }
                    }
                }
            }
        }

        // 4. Kausal-/Begründungs-Karten
        if (FlashcardType.CAUSAL in enabledTypes) {
            for (p in passages) {
                for (s in p.classifiedSentences) {
                    if (SentenceRole.REASON_GOAL in s.roles || SentenceRole.CAUSE_RISK in s.roles) {
                        buildCausalCard(config.title, p, s)?.let { candidates.add(it) }
                    }
                }
            }
        }

        // 5. Lückentext-Karten (Cloze) aus zentralen, aussagekräftigen Sätzen
        if (FlashcardType.CLOZE in enabledTypes) {
            for (p in passages) {
                for (s in p.classifiedSentences) {
                    buildClozeCard(config.title, p, s)?.let { candidates.add(it) }
                }
            }
        }

        // Deduplizieren und balanciert nach gewünschten Kartentypen auswählen
        val deduplicated = mutableListOf<GeneratedFlashcard>()
        for (card in candidates) {
            val isDuplicate = deduplicated.any { existing ->
                GermanTextAnalyzer.jaccardSimilarity(existing.backText, card.backText) >= 0.72 ||
                    GermanTextAnalyzer.jaccardSimilarity(existing.frontText, card.frontText) >= 0.80
            }
            if (!isDuplicate) {
                deduplicated.add(card)
            }
        }

        if (deduplicated.size <= targetCount) return deduplicated

        // Round-Robin über Kartentypen für abwechslungsreiches Kartendeck
        val byType = deduplicated.groupBy { it.cardType }.mapValues { it.value.toMutableList() }
        val typeOrder = enabledTypes.toList()
        val balanced = mutableListOf<GeneratedFlashcard>()
        var addedInRound = true
        while (balanced.size < targetCount && addedInRound) {
            addedInRound = false
            for (t in typeOrder) {
                val list = byType[t]
                if (!list.isNullOrEmpty() && balanced.size < targetCount) {
                    balanced.add(list.removeAt(0))
                    addedInRound = true
                }
            }
        }
        return balanced
    }

    private fun buildDefinitionCard(
        topicTitle: String,
        passage: RetrievedPassage,
        sentence: ClassifiedSentence
    ): GeneratedFlashcard? {
        val clean = GermanTextAnalyzer.stripBulletPrefix(sentence.text)
        if (clean.length < 25) return null
        val citation = citationOf(passage)

        // Muster: "Begriff: Erklärung" oder "Unter X versteht man ..." oder "X bezeichnet ..."
        val rawColonMatch = Regex("""^(?:Definition\s*:\s*)?([A-ZÄÖÜ][^:]{2,50}):\s+(.+)$""").find(clean)
        val ignoredColonTerms = setOf(
            "cave", "achtung", "wichtig", "merke", "merksatz", "hinweis", "warnhinweis",
            "kontraindikation", "beispiel", "ziel", "ursache", "symptom", "maßnahme", "begründung"
        )
        val colonMatch = rawColonMatch?.takeIf {
            it.groupValues[1].trim().lowercase(germanLocale) !in ignoredColonTerms
        }
        val verstehtManMatch = Regex("""^Unter\s+(?:einem|einer|dem|der)?\s*([^,.;]{3,55})\s+versteht\s+man\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
        val bezeichnetMatch = Regex("""^(?:Als|Der|Die|Das)?\s*([A-ZÄÖÜ][A-Za-zÄÖÜäöüß\-\s]{2,45})\s+(?:bezeichnet|ist|sind)\s+(.+)$""").find(clean)

        val front = when {
            colonMatch != null -> {
                val term = colonMatch.groupValues[1].trim()
                "Wie wird „$term“ laut Quelle (${citation.shortLabel()}) definiert?"
            }
            verstehtManMatch != null -> {
                val term = verstehtManMatch.groupValues[1].trim()
                "Was versteht man laut Quelle (${citation.shortLabel()}) unter „$term“?"
            }
            bezeichnetMatch != null -> {
                val term = bezeichnetMatch.groupValues[1].trim()
                "Erläutern Sie den Begriff „$term“ anhand Ihrer Quelle ($topicTitle)."
            }
            else -> {
                val heading = passage.chunk.sectionHeading.takeIf { it != "Allgemein" } ?: topicTitle
                "Welche Definition nennt die Quelle zu „$heading“?"
            }
        }

        return GeneratedFlashcard(
            cardType = FlashcardType.DEFINITION,
            frontText = front,
            backText = "$clean\n\nBeleg: ${citation.shortLabel()}",
            citationLabel = citation.shortLabel(),
            sourceExcerpt = clean,
            parameterTag = "Definition"
        )
    }

    private fun buildListCard(
        topicTitle: String,
        passage: RetrievedPassage
    ): GeneratedFlashcard? {
        val listSentences = passage.classifiedSentences
            .filter { it.isListItem }
            .map { GermanTextAnalyzer.stripBulletPrefix(it.text) }
            .filter { it.length >= 10 }
            .distinct()
            .take(6)

        if (listSentences.size < 2) return null

        val citation = citationOf(passage)
        val heading = passage.chunk.sectionHeading.takeIf { it != "Allgemein" } ?: topicTitle
        val front = "Nennen Sie ${listSentences.size} in der Quelle (${citation.shortLabel()}) genannte Aspekte zu „$heading“."
        val back = listSentences.mapIndexed { idx, item -> "${idx + 1}. $item" }
            .joinToString("\n") + "\n\nBeleg: ${citation.shortLabel()}"

        return GeneratedFlashcard(
            cardType = FlashcardType.LIST,
            frontText = front,
            backText = back,
            citationLabel = citation.shortLabel(),
            sourceExcerpt = listSentences.joinToString(" | "),
            parameterTag = "Aufzählung"
        )
    }

    private fun buildCausalCard(
        topicTitle: String,
        passage: RetrievedPassage,
        sentence: ClassifiedSentence
    ): GeneratedFlashcard? {
        val clean = GermanTextAnalyzer.stripBulletPrefix(sentence.text)
        if (clean.length < 32) return null
        val citation = citationOf(passage)

        // Spaltung an kausalen Konjunktionen: "Maßnahme/Fakt, weil Begründung"
        val weilSplit = Regex("""^(.{15,140}?),\s+(?:weil|da|denn)\s+(.{12,})$""", RegexOption.IGNORE_CASE).find(clean)
        val umZuSplit = Regex("""^(.{15,140}?),?\s+um\s+(.{10,}\s+zu\b.*)$""", RegexOption.IGNORE_CASE).find(clean)
        val fuehrtZuSplit = Regex("""^(.{10,120}?)\s+(?:führt zu|bewirkt|verursacht|begünstigt)\s+(.{10,})$""", RegexOption.IGNORE_CASE).find(clean)

        val (front, back) = when {
            weilSplit != null -> {
                val claim = weilSplit.groupValues[1].trim()
                val reason = weilSplit.groupValues[2].trim().removeSuffix(".")
                "Warum gilt laut Quelle (${citation.shortLabel()}): „$claim“?" to
                    "Begründung: Weil $reason.\n\nVollständige Quellenstelle: $clean"
            }
            umZuSplit != null -> {
                val action = umZuSplit.groupValues[1].trim()
                val goal = umZuSplit.groupValues[2].trim().removeSuffix(".")
                "Mit welchem Ziel erfolgt laut Quelle: „$action“?" to
                    "Ziel: Um $goal.\n\nBeleg: ${citation.shortLabel()}"
            }
            fuehrtZuSplit != null -> {
                val cause = fuehrtZuSplit.groupValues[1].trim()
                "Welche Auswirkung bzw. Folge nennt die Quelle bei: „$cause“?" to
                    "$clean\n\nBeleg: ${citation.shortLabel()}"
            }
            else -> {
                val heading = passage.chunk.sectionHeading.takeIf { it != "Allgemein" } ?: topicTitle
                "Welchen Zusammenhang / welche Ursache beschreibt die Quelle bei „$heading“?" to
                    "$clean\n\nBeleg: ${citation.shortLabel()}"
            }
        }

        return GeneratedFlashcard(
            cardType = FlashcardType.CAUSAL,
            frontText = front,
            backText = back,
            citationLabel = citation.shortLabel(),
            sourceExcerpt = clean,
            parameterTag = "Begründung / Ursache"
        )
    }

    private fun buildCaveCard(
        topicTitle: String,
        passage: RetrievedPassage,
        sentence: ClassifiedSentence
    ): GeneratedFlashcard? {
        val clean = GermanTextAnalyzer.stripBulletPrefix(sentence.text)
        if (clean.length < 25) return null
        val citation = citationOf(passage)
        val heading = passage.chunk.sectionHeading.takeIf { it != "Allgemein" } ?: topicTitle

        val front = "Worauf ist laut Quelle (${citation.shortLabel()}) bei „$heading“ besonders zu achten (Cave / Kontraindikation / Merksatz)?"
        val back = "$clean\n\nBeleg: ${citation.shortLabel()}"

        return GeneratedFlashcard(
            cardType = FlashcardType.CAVE,
            frontText = front,
            backText = back,
            citationLabel = citation.shortLabel(),
            sourceExcerpt = clean,
            parameterTag = "Cave / Merksatz"
        )
    }

    private fun buildClozeCard(
        topicTitle: String,
        passage: RetrievedPassage,
        sentence: ClassifiedSentence
    ): GeneratedFlashcard? {
        val clean = GermanTextAnalyzer.stripBulletPrefix(sentence.text)
        if (clean.length !in 38..280) return null

        val terms = GermanTextAnalyzer.extractDistinctiveTerms(clean)
            .filter { term ->
                val lower = term.lowercase(germanLocale)
                val titleLower = topicTitle.lowercase(germanLocale)
                // Nicht exakt nur das Thema selbst ausblenden, sondern spezifische Fakten/Werte/Begriffe
                lower != titleLower && term.length >= 4 && !clean.startsWith(term)
            }

        val targetTerm = terms
            .sortedByDescending { term ->
                var weight = term.length
                if (term.any { it.isDigit() }) weight += 8 // Messwerte / Stadien bevorzugt
                if (term.first().isUpperCase()) weight += 3
                weight
            }
            .firstOrNull() ?: return null

        val escaped = Regex.escape(targetTerm)
        val clozeSentence = clean.replaceFirst(Regex("""\b$escaped\b"""), "[____]")
        if (clozeSentence == clean) return null

        val citation = citationOf(passage)
        val heading = passage.chunk.sectionHeading.takeIf { it != "Allgemein" } ?: topicTitle

        return GeneratedFlashcard(
            cardType = FlashcardType.CLOZE,
            frontText = "Lückentext ($heading · ${citation.shortLabel()}):\n\n$clozeSentence",
            backText = "Gesuchter Begriff: $targetTerm\n\nVollständige Aussage:\n$clean",
            citationLabel = citation.shortLabel(),
            sourceExcerpt = clean,
            parameterTag = "Lückentext"
        )
    }

    private fun citationOf(passage: RetrievedPassage): SourceCitation {
        val c = passage.chunk
        return SourceCitation(
            sourceId = c.sourceId,
            sourceTitle = c.sourceTitle,
            pageLabel = c.pageLabel(),
            sectionHeading = c.sectionHeading,
            excerpt = c.content.take(360)
        )
    }
}
