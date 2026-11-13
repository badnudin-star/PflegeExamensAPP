package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ClassifiedSentence
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.SentenceRole
import java.util.Locale

data class DraftChunk(
    val chunkIndex: Int,
    val pageNumber: Int?,
    val sectionHeading: String,
    val content: String,
    val normalizedTokens: String
)

/**
 * Zerlegt importierte Dokumente (PDF-Seiten, DOCX-Abschnitte, TXT/MD, OCR-Scans)
 * in strukturierte Chunks mit Seitenzahl und erkannter Überschrift, und klassifiziert
 * Sätze nach typischen fachlichen Rollen in der Pflegeausbildung.
 */
object DocumentChunker {

    private const val TARGET_MAX_CHUNK_CHARS = 780
    private const val HARD_MAX_CHUNK_CHARS = 1050

    private val germanLocale = Locale.GERMAN

    fun chunkPages(pages: List<RawPage>): List<DraftChunk> {
        val chunks = mutableListOf<DraftChunk>()
        var chunkIdx = 0
        var activeHeading = "Allgemein"

        for (page in pages) {
            val cleaned = GermanTextAnalyzer.cleanRawText(page.text)
            if (cleaned.isBlank()) continue

            val blocks = splitIntoParagraphBlocks(cleaned)
            val buffer = StringBuilder()
            var blockHeading = activeHeading

            fun flushBuffer() {
                val text = buffer.toString().trim()
                buffer.clear()
                if (text.length < 15) return
                val tokens = GermanTextAnalyzer.tokenizeKeywords(
                    "$blockHeading $text",
                    includeStems = true
                ).joinToString(" ")
                chunks.add(
                    DraftChunk(
                        chunkIndex = chunkIdx++,
                        pageNumber = page.pageNumber,
                        sectionHeading = blockHeading,
                        content = text,
                        normalizedTokens = tokens
                    )
                )
            }

            for (block in blocks) {
                val headingCandidate = extractHeadingIfStandalone(block)
                if (headingCandidate != null) {
                    if (buffer.isNotEmpty()) {
                        flushBuffer()
                    }
                    activeHeading = headingCandidate
                    blockHeading = headingCandidate
                    continue
                }

                // Inline heading line at start of multi-line block
                val lines = block.lines()
                val firstLine = lines.firstOrNull()?.trim().orEmpty()
                val rest = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else ""
                val effectiveText: String
                if (lines.size > 1 && isLikelyHeading(firstLine) && rest.isNotBlank()) {
                    if (buffer.isNotEmpty()) {
                        flushBuffer()
                    }
                    activeHeading = normalizeHeading(firstLine)
                    blockHeading = activeHeading
                    effectiveText = rest
                } else {
                    effectiveText = block
                }

                if (effectiveText.length > HARD_MAX_CHUNK_CHARS) {
                    if (buffer.isNotEmpty()) flushBuffer()
                    for (sub in splitLongBlockBySentences(effectiveText)) {
                        buffer.append(sub)
                        flushBuffer()
                    }
                    continue
                }

                if (buffer.isNotEmpty() && buffer.length + effectiveText.length + 2 > TARGET_MAX_CHUNK_CHARS) {
                    flushBuffer()
                    blockHeading = activeHeading
                }
                if (buffer.isNotEmpty()) buffer.append("\n\n")
                buffer.append(effectiveText)
            }
            flushBuffer()
        }
        return chunks
    }

    private fun splitIntoParagraphBlocks(cleaned: String): List<String> {
        return cleaned
            .split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun splitLongBlockBySentences(text: String): List<String> {
        val sentences = GermanTextAnalyzer.splitSentences(text)
        if (sentences.isEmpty()) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > TARGET_MAX_CHUNK_CHARS) {
                out.add(cur.toString().trim())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    private fun extractHeadingIfStandalone(block: String): String? {
        val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size != 1) return null
        val line = lines.first()
        return if (isLikelyHeading(line)) normalizeHeading(line) else null
    }

    fun isLikelyHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3 || trimmed.length > 90) return false
        if (GermanTextAnalyzer.isBulletLine(trimmed)) return false
        if (trimmed.startsWith("#")) return true
        // Numbered heading e.g. "1.2 Dekubitusprophylaxe" without sentence ending
        if (Regex("""^\d+(?:\.\d+)*\.?\s+[A-ZÄÖÜ].+[^.!?]$""").matches(trimmed) && trimmed.length <= 80) {
            return true
        }
        // Short title without terminal period/question/exclamation (except trailing colon)
        val withoutColon = trimmed.removeSuffix(":")
        val wordCount = withoutColon.split(Regex("""\s+""")).size
        if (wordCount in 1..9 &&
            withoutColon.first().isUpperCase() &&
            !withoutColon.endsWith(".") &&
            !withoutColon.endsWith("!") &&
            !withoutColon.endsWith("?") &&
            !withoutColon.endsWith(",") &&
            !withoutColon.endsWith(";")
        ) {
            return true
        }
        return false
    }

    private fun normalizeHeading(line: String): String {
        return line.trim()
            .removePrefix("###").removePrefix("##").removePrefix("#")
            .removeSuffix(":")
            .trim()
    }

    fun classifyChunkSentences(content: String): List<ClassifiedSentence> {
        return GermanTextAnalyzer.splitSentences(content).map { sentence ->
            val isList = GermanTextAnalyzer.isBulletLine(sentence)
            val roles = classifySentenceRoles(sentence, isList)
            ClassifiedSentence(
                text = sentence,
                roles = roles,
                isListItem = isList
            )
        }
    }

    fun classifySentenceRoles(sentence: String, isListItem: Boolean = GermanTextAnalyzer.isBulletLine(sentence)): Set<SentenceRole> {
        val lower = sentence.lowercase(germanLocale)
        val roles = LinkedHashSet<SentenceRole>()
        if (isListItem) roles.add(SentenceRole.LIST_ITEM)

        val nonDefinitionPrefixes = setOf(
            "cave", "achtung", "wichtig", "merke", "merksatz", "hinweis", "warnhinweis",
            "kontraindikation", "beispiel", "ziel", "ursache", "symptom", "anzeichen",
            "maßnahme", "begründung", "situation", "fallbeispiel", "anamnese", "ergebnis"
        )
        val colonPrefix = Regex("""^([A-ZÄÖÜ][A-Za-zÄÖÜäöüß\-\s]{2,40}):\s+\S+""")
            .find(sentence)?.groupValues?.getOrNull(1)?.trim()?.lowercase(germanLocale)
        val isColonDefinition = colonPrefix != null && colonPrefix !in nonDefinitionPrefixes

        if (lower.startsWith("definition") ||
            lower.contains("versteht man") ||
            lower.contains("bezeichnet man") ||
            lower.contains("bezeichnet ") ||
            lower.contains("definiert als") ||
            lower.contains("ist eine ") ||
            lower.contains("ist ein ") ||
            lower.contains("sind erkrankungen") ||
            lower.contains("besteht aus") ||
            lower.contains("gliedert sich in") ||
            lower.contains("unterteilt in") ||
            lower.contains("anatomisch") ||
            lower.contains("physiologisch") ||
            isColonDefinition
        ) {
            roles.add(SentenceRole.DEFINITION)
        }

        if (containsAny(
                lower,
                "ursache", "auslöser", "ausgelöst", "risiko", "risikofaktor", "begünstigt",
                "entsteht", "ätiologie", "einflussfaktor", "druckeinwirkung", "scherkräfte",
                "prädisposition", "mangelernährung", "immobilität"
            )
        ) {
            roles.add(SentenceRole.CAUSE_RISK)
        }

        if (containsAny(
                lower,
                "symptom", "anzeichen", "befund", "zeigt sich", "erscheinungsbild",
                "beobacht", "erfass", "skala", "assessment", "screening", "grad ",
                "kategorie", "stadium", "stadien", "mmhg", "mg/dl", "°c", "schmerz",
                "rötung", "fingertest", "atemfrequenz", "puls", "blutdruck"
            )
        ) {
            roles.add(SentenceRole.SYMPTOM_ASSESSMENT)
        }

        if (containsAny(
                lower,
                "maßnahme", "intervention", "prophylaxe", "durchführ", "lagerung",
                "positionswechsel", "mobilisation", "mobilisier", "verabreich",
                "anleit", "berat", "unterstütz", "förder", "überwach", "kontroll",
                "dokumentier", "pflegeplan", "pflegeziel", "kompression", "hautpflege",
                "therapie", "atemübung", "hygiene", "desinfektion"
            )
        ) {
            roles.add(SentenceRole.INTERVENTION_MEASURE)
        }

        if (containsAny(
                lower,
                "weil ", "da ", "denn ", "dadurch", "führt zu", "um ", "damit ",
                "ziel ", "zweck", "dient der", "dient dazu", "verhindert", "begründung",
                "indikation", "folge ist", "aufgrund", "durch die "
            )
        ) {
            roles.add(SentenceRole.REASON_GOAL)
        }

        if (containsAny(
                lower,
                "cave", "achtung", "wichtig:", "merke:", "merksatz", "kontraindikation",
                "niemals", "keinesfalls", "darf nicht", "dürfen nicht", "vermeiden",
                "gefahr", "komplikation", "warnhinweis", "sofort", "notfall"
            )
        ) {
            roles.add(SentenceRole.CAVE_WARNING)
        }

        if (containsAny(
                lower,
                "§", "gesetz", "pflbg", "pflaprv", "sgb", "schweigepflicht",
                "delegation", "einwilligung", "rechtlich", "haftung", "ethik",
                "autonomie", "datenschutz", "schweigepflicht", "betreuungsrecht",
                "patientenverfügung", "vorsorgevollmacht"
            )
        ) {
            roles.add(SentenceRole.LEGAL_ETHICS)
        }

        if (containsAny(
                lower,
                "fallbeispiel", "situationsbeschreibung", "frau ", "herr ",
                "patientin", "patient ", "bewohnerin", "bewohner ", "klientin",
                "klient ", "jahre alt", "anamnese", "aufnahmegrund", "stationär",
                "pflegeheim", "ambulante pflege"
            )
        ) {
            roles.add(SentenceRole.CASE_CONTEXT)
        }

        if (roles.isEmpty()) {
            roles.add(SentenceRole.GENERAL)
        }
        return roles
    }

    private fun containsAny(text: String, vararg markers: String): Boolean {
        return markers.any { text.contains(it) }
    }
}
