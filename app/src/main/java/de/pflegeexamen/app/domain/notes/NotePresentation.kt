package de.pflegeexamen.app.domain.notes

/** Display-only transformation. The stored source text is never modified. */
data class NoteSection(val heading: String?, val paragraphs: List<String>)
data class NoteBlock(val text: String, val isHeading: Boolean = false)

object NotePresentation {
    private val oldHeader = Regex("(?m)^EXAMEN 2027 \\| LERNZETTEL[ \\t]*\\r?\\n[0-9]+[ \\t]*(?:\\r?\\n|$)")
    private val newHeader = Regex("^EXAMENSVORBEREITUNG · (LERNZETTEL|MUSTERLÖSUNGEN)(?:\\s+\\d+)?$")
    private val oldFooter = Regex("(?m)^Quellen und Einordnung[ \\t]*\\nFundstellen bezeichnen PDF-Seiten der Originaldateien\\.")
    private val variant = Regex("^(Kurz|Ausgeglichen|Ausführlich) · Lernzettel zum Examen.*$")
    private val oldHeading = Regex("^[^\\[\\]]{1,180}\\[[^\\[\\]]{1,240}]$")
    private val bullet = Regex("^(?:[•●▪]|[-*]\\s|[0-9]+[.)]\\s).*")
    private val numberedHeading = Regex("^\\d+\\s*·\\s*[^a-z]{3,150}$")
    private fun upperHeading(line: String) = line.length in 3..120 && line.any { it.isLetter() } &&
        line.any { it.isUpperCase() } && line.none { it.isLowerCase() } && !line.endsWith(".")

    fun sections(raw: String): List<NoteSection> {
        val normal = raw.replace("\r\n", "\n").replace('\r', '\n')
        val old = oldHeader.containsMatchIn(normal)
        val modern = normal.lines().any { it.trim().startsWith("EXAMENSVORBEREITUNG ·") }
        val noHeaders = normal.replace(oldHeader, "")
        val footerStart = if (old) oldFooter.find(noHeaders)?.range?.first else null
        val lines = (if (footerStart != null) noHeaders.substring(0, footerStart) else noHeaders)
            .lines().map(String::trim).toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        if (old && lines.size >= 2 && variant.matches(lines[1])) { lines.removeAt(0); lines.removeAt(0) }
        val sections = mutableListOf<NoteSection>()
        var currentHeading: String? = null
        val paragraphs = mutableListOf<String>()
        var pending = StringBuilder()
        fun flushParagraph() { if (pending.isNotBlank()) paragraphs.add(pending.toString().trim()); pending = StringBuilder() }
        fun flushSection() {
            flushParagraph()
            if (currentHeading != null || paragraphs.isNotEmpty()) sections.add(NoteSection(currentHeading, paragraphs.toList()))
            currentHeading = null; paragraphs.clear()
        }
        var skipSources = false
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            if (modern && newHeader.matches(line)) { flushParagraph(); skipSources = false; i++; continue }
            if (modern && line.matches(Regex("^\\d{1,3}$"))) { i++; continue }
            if (modern && line.matches(Regex("(?i)^QUELLEN(?: UND GRENZEN)?$"))) { flushSection(); skipSources = true; i++; continue }
            if (skipSources) {
                if (upperHeading(line) && !line.startsWith("ABBILDUNG")) skipSources = false
                else { i++; continue }
            }
            if (line.contains('[') && !line.contains(']') && i + 1 < lines.size && lines[i + 1].contains(']')) {
                val combined = "$line ${lines[i + 1]}"
                if (oldHeading.matches(combined)) { line = combined; i++ }
            }
            when {
                line.isEmpty() -> flushParagraph()
                oldHeading.matches(line) || (modern && (upperHeading(line) || numberedHeading.matches(line))) -> {
                    flushSection(); currentHeading = line
                }
                else -> {
                    if (bullet.matches(line)) flushParagraph()
                    if (pending.isNotEmpty()) pending.append(' ')
                    pending.append(line)
                }
            }
            i++
        }
        flushSection()
        return sections
    }

    fun blocks(raw: String): List<NoteBlock> = sections(raw).flatMap { section ->
        listOfNotNull(section.heading?.let { NoteBlock(it, true) }) + section.paragraphs.map { NoteBlock(it) }
    }
    fun firstMatch(blocks: List<NoteBlock>, query: String): Int? {
        val term = query.trim()
        if (term.isEmpty()) return null
        return blocks.indexOfFirst { it.text.contains(term, ignoreCase = true) }.takeIf { it >= 0 }
    }
}
