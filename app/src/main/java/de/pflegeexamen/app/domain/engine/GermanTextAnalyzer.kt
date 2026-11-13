package de.pflegeexamen.app.domain.engine

import java.util.Locale

/**
 * Reine Kotlin-Textanalyse für deutsche Pflege- und Medizinfachtexte (100 % offline).
 * Unterstützt deutsche Umlaute, Abkürzungen, Komposita- und Stamm-Abgleich.
 */
object GermanTextAnalyzer {

    private val germanLocale = Locale.GERMAN

    val stopWords: Set<String> = setOf(
        "aber", "alle", "allem", "allen", "aller", "alles", "als", "also", "am", "an",
        "ander", "andere", "anderem", "anderen", "anderer", "anderes", "anderm", "anders",
        "auch", "auf", "aus", "bei", "beide", "beiden", "beim", "beispiel", "bereits",
        "besonders", "bevor", "bin", "bis", "bist", "bzw", "da", "dabei", "dadurch",
        "dafür", "dagegen", "daher", "damit", "danach", "dann", "darauf", "daraus",
        "darf", "darüber", "darum", "darunter", "das", "dass", "davon", "davor",
        "dazu", "dein", "deine", "dem", "demselben", "den", "denen", "denn", "denselben",
        "der", "deren", "derer", "derselben", "des", "deshalb", "dessen", "desto",
        "die", "dies", "diese", "dieselbe", "dieselben", "diesem", "diesen", "dieser",
        "dieses", "doch", "dort", "du", "durch", "durchaus", "dürfen", "ebenso",
        "ein", "eine", "einem", "einen", "einer", "eines", "einige", "einigem",
        "einigen", "einiger", "einiges", "einmal", "entweder", "er", "erst", "es",
        "etwa", "etwas", "euch", "euer", "eure", "falls", "fast", "ferner", "folgende",
        "folgenden", "folgender", "für", "ganz", "gar", "gegen", "gegenüber", "gemäß",
        "genau", "gewesen", "gibt", "haben", "habt", "hat", "hatte", "hatten", "hier",
        "hin", "hinter", "ich", "ihm", "ihn", "ihnen", "ihr", "ihre", "ihrem", "ihren",
        "ihrer", "ihres", "im", "immer", "in", "indem", "infolge", "innen", "innerhalb",
        "ins", "insbesondere", "ist", "ja", "jede", "jedem", "jeden", "jeder", "jedes",
        "jedoch", "jemals", "jenem", "jenen", "jener", "jenes", "jetzt", "kann",
        "kannst", "kein", "keine", "keinem", "keinen", "keiner", "keines", "können",
        "könnte", "könnten", "lassen", "lässt", "laut", "leider", "machen", "man",
        "manche", "manchem", "manchen", "mancher", "manches", "mehr", "mein", "meine",
        "meinem", "meinen", "meiner", "meines", "meist", "mit", "mittels", "möchte",
        "möglich", "muss", "müssen", "musste", "müsste", "nach", "nachdem", "neben",
        "nein", "nicht", "nichts", "nie", "niemals", "noch", "nun", "nur", "ob",
        "ober", "obwohl", "oder", "ohne", "pro", "rund", "schon", "sehr", "sein",
        "seine", "seinem", "seinen", "seiner", "seines", "seit", "seitdem", "selbst",
        "sich", "sie", "siehe", "sind", "so", "sobald", "sodass", "sofern", "sofort",
        "sogar", "solange", "solch", "solche", "solchem", "solchen", "solcher", "solches",
        "soll", "sollen", "sollte", "sollten", "somit", "sondern", "sonst", "sowie",
        "sowohl", "später", "statt", "stets", "trotz", "über", "überall", "um",
        "und", "uns", "unser", "unsere", "unserem", "unseren", "unserer", "unter",
        "usw", "viel", "viele", "vielem", "vielen", "vieler", "vieles", "vielleicht",
        "vom", "von", "vor", "vor allem", "während", "wann", "war", "waren", "warum",
        "was", "weder", "wegen", "weil", "weit", "weiter", "weitere", "weiteren",
        "welche", "welchem", "welchen", "welcher", "welches", "wenn", "wer", "werden",
        "weshalb", "wie", "wieder", "wird", "wir", "wirst", "wo", "wobei", "wodurch",
        "woher", "wohin", "wollen", "wollte", "worauf", "worin", "wovon", "wurde",
        "wurden", "würde", "würden", "z.b", "zb", "zu", "zudem", "zuerst", "zum",
        "zur", "zurück", "zusammen", "zwischen", "zwar"
    )

    private val protectedAbbreviations = listOf(
        "z. B.", "z.B.", "u. a.", "u.a.", "d. h.", "d.h.", "bzw.", "ca.", "evtl.",
        "ggf.", "inkl.", "max.", "min.", "mind.", "Nr.", "S.", "Abs.", "Tab.", "Abb.",
        "Dr.", "Prof.", "usw.", "etc.", "vgl.", "o. ä.", "o.ä.", "s. o.", "s.o.",
        "s. u.", "s.u.", "i. d. R.", "i.d.R.", "u. U.", "u.U.", "v. a.", "v.a.",
        "m. E.", "St.", "Med.", "Pat.", "Bew."
    )

    private val tokenRegex = Regex("""[A-Za-zÄÖÜäöüß0-9]+(?:-[A-Za-zÄÖÜäöüß0-9]+)*""")
    private val hyphenLineBreakRegex = Regex("""([A-Za-zÄÖÜäöüß])-\r?\n([a-zäöüß])""")
    private val multiSpaceRegex = Regex("""[ \t]+""")

    /**
     * Bereinigt typische PDF- und OCR-Umbrüche (z. B. Silbentrennung am Zeilenende).
     */
    fun cleanRawText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u00AD", "") // soft hyphen
            .replace(hyphenLineBreakRegex, "$1$2")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .lines()
            .joinToString("\n") { line ->
                line.trimEnd().replace(multiSpaceRegex, " ")
            }
            .trim()
    }

    fun normalizeForMatching(text: String): String {
        return text.lowercase(germanLocale)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
    }

    fun stemGerman(word: String): String {
        var w = word.lowercase(germanLocale).trim()
        if (w.length <= 4) return w
        val suffixes = listOf(
            "ungen", "heiten", "keiten", "schaften", "tionen",
            "ische", "ischer", "isches", "ischen",
            "ung", "heit", "keit", "schaft", "tion",
            "lich", "ig", "ern", "ten", "en", "er", "es", "em", "te", "e", "s"
        )
        for (suffix in suffixes) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 4) {
                w = w.dropLast(suffix.length)
                break
            }
        }
        return w
    }

    /**
     * Zerlegt Text in normalisierte, nicht-stoppwortige Such-Tokens (inkl. Komposita-Teilstücke bei Bindestrich).
     */
    fun tokenizeKeywords(text: String, includeStems: Boolean = true): List<String> {
        val result = LinkedHashSet<String>()
        for (match in tokenRegex.findAll(text)) {
            val rawToken = match.value.lowercase(germanLocale)
            if (rawToken.length < 2 || rawToken in stopWords) continue
            result.add(rawToken)
            val normalized = normalizeForMatching(rawToken)
            result.add(normalized)
            if (includeStems) {
                result.add(stemGerman(rawToken))
                result.add(stemGerman(normalized))
            }
            if ('-' in rawToken) {
                for (part in rawToken.split('-')) {
                    if (part.length >= 3 && part !in stopWords) {
                        result.add(part)
                        if (includeStems) result.add(stemGerman(part))
                    }
                }
            }
        }
        return result.toList()
    }

    /**
     * Prüft, ob ein Suchbegriff/Keyword-Token im Ziel-Token-Set oder im normalisierten Volltext
     * (inkl. Komposita-Erkennung für deutsche Fachbegriffe >= 5 Zeichen) vorkommt.
     */
    fun matchesTerm(queryTerm: String, targetTokens: Set<String>, normalizedTargetText: String): Boolean {
        val qLower = queryTerm.lowercase(germanLocale).trim()
        if (qLower.length < 2) return false
        val qNorm = normalizeForMatching(qLower)
        val qStem = stemGerman(qLower)
        val qNormStem = stemGerman(qNorm)

        if (qLower in targetTokens || qNorm in targetTokens || qStem in targetTokens || qNormStem in targetTokens) {
            return true
        }
        // Deutsche Komposita-Erkennung (z. B. "Dekubitus" in "Dekubitusprophylaxe")
        if (qNormStem.length >= 5 && normalizedTargetText.contains(qNormStem)) {
            return true
        }
        if (qNorm.length >= 5 && normalizedTargetText.contains(qNorm)) {
            return true
        }
        return false
    }

    /**
     * Zerlegt deutschen Fließtext und Listen sauber in Sätze/Aufzählungspunkte,
     * ohne Abkürzungen wie "z. B.", "u. a." oder Zahlen wie "1." zu zerschneiden.
     */
    fun splitSentences(text: String): List<String> {
        val cleaned = cleanRawText(text)
        if (cleaned.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        val lines = cleaned.lines()
        val paragraphBuffer = StringBuilder()

        fun flushParagraph() {
            val p = paragraphBuffer.toString().trim()
            paragraphBuffer.clear()
            if (p.isBlank()) return

            var protected = p
            val placeholders = mutableMapOf<String, String>()
            protectedAbbreviations.forEachIndexed { idx, abbr ->
                if (protected.contains(abbr)) {
                    val token = "__ABBR_${idx}__"
                    placeholders[token] = abbr
                    protected = protected.replace(abbr, token)
                }
            }
            // Schütze Dezimal- und Ordnungszahlen wie "1. " oder "38.5" oder "§ 14."
            protected = protected.replace(Regex("""(\d)\.(?=\d)"""), "$1__DOT__")
            protected = protected.replace(Regex("""(^|\s)(\d{1,2})\.\s+""")) { m ->
                "${m.groupValues[1]}${m.groupValues[2]}__ORD__ "
            }

            val rawSentences = protected.split(Regex("""(?<=[.!?])\s+(?=[A-ZÄÖÜ0-9„"(\[])"""))
            for (rawS in rawSentences) {
                var restored = rawS
                    .replace("__DOT__", ".")
                    .replace("__ORD__", ".")
                for ((token, abbr) in placeholders) {
                    restored = restored.replace(token, abbr)
                }
                val finalSentence = restored.trim()
                if (finalSentence.length >= 8) {
                    result.add(finalSentence)
                }
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                flushParagraph()
                continue
            }
            val isBullet = isBulletLine(trimmed)
            if (isBullet) {
                flushParagraph()
                val normalizedBullet = trimmed
                    .replace(Regex("""^[•●▪◦\-*]\s*"""), "• ")
                if (normalizedBullet.length >= 6) {
                    result.add(normalizedBullet)
                }
            } else {
                if (paragraphBuffer.isNotEmpty()) {
                    paragraphBuffer.append(' ')
                }
                paragraphBuffer.append(trimmed)
            }
        }
        flushParagraph()
        return result
    }

    fun isBulletLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.startsWith("•") ||
            trimmed.startsWith("●") ||
            trimmed.startsWith("▪") ||
            Regex("""^\d{1,2}[.)]\s+\S+""").containsMatchIn(trimmed) ||
            Regex("""^[a-zA-Z]\)\s+\S+""").containsMatchIn(trimmed)
    }

    fun stripBulletPrefix(line: String): String {
        return line.trim()
            .replace(Regex("""^[•●▪◦\-*]\s*"""), "")
            .replace(Regex("""^\d{1,2}[.)]\s+"""), "")
            .replace(Regex("""^[a-zA-Z]\)\s+"""), "")
            .trim()
    }

    /**
     * Extrahiert aussagekräftige Fachbegriffe/Werte aus einem Satz (ohne Stoppwörter).
     */
    fun extractDistinctiveTerms(text: String): List<String> {
        return tokenRegex.findAll(text)
            .map { it.value }
            .filter { token ->
                val lower = token.lowercase(germanLocale)
                lower !in stopWords && (
                    token.first().isUpperCase() && lower.length >= 4 ||
                        token.any { it.isDigit() } ||
                        lower.length >= 6
                    )
            }
            .distinctBy { it.lowercase(germanLocale) }
            .toList()
    }

    fun jaccardSimilarity(a: String, b: String): Double {
        val setA = tokenizeKeywords(a, includeStems = true).toSet()
        val setB = tokenizeKeywords(b, includeStems = true).toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }
}
