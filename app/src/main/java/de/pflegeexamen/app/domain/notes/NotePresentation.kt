package de.pflegeexamen.app.domain.notes

/** Display-only transformation. The stored source text is never modified. */
data class NoteSection(val heading: String?, val paragraphs: List<String>)

object NotePresentation {
    private val pageHeader = Regex("(?m)^EXAMEN 2027 \\| LERNZETTEL[ \\t]*\\r?\\n[0-9]+[ \\t]*(?:\\r?\\n|$)")
    private val footer = Regex("(?m)^Quellen und Einordnung[ \\t]*\\nFundstellen bezeichnen PDF-Seiten der Originaldateien\\.")
    private val variant = Regex("^(Kurz|Ausgeglichen|Ausführlich) · Lernzettel zum Examen.*$")
    private val heading = Regex("^[^\\[\\]]{1,180}\\[[^\\[\\]]{1,240}]$")
    private val bullet = Regex("^(?:[•●▪]|[-*]\\s|[0-9]+[.)]\\s).*")

    fun sections(raw: String): List<NoteSection> {
        val normal = raw.replace("\r\n", "\n").replace('\r', '\n')
        val isPdfExport = pageHeader.containsMatchIn(normal)
        val withoutHeaders = normal.replace(pageHeader, "")
        val footerStart = if (isPdfExport) footer.find(withoutHeaders)?.range?.first else null
        val lines = (if (footerStart != null) withoutHeaders.substring(0, footerStart) else withoutHeaders)
            .lines().map(String::trim).toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        // The PDF title is already displayed in the app bar; keep unknown formats intact.
        if (isPdfExport && lines.size >= 2 && variant.matches(lines[1])) {
            lines.removeAt(0); lines.removeAt(0)
        }
        val sections = mutableListOf<NoteSection>()
        var currentHeading: String? = null
        val paragraphs = mutableListOf<String>()
        var pending = StringBuilder()
        fun flushParagraph() {
            if (pending.isNotBlank()) paragraphs.add(pending.toString().trim())
            pending = StringBuilder()
        }
        fun flushSection() {
            flushParagraph()
            if (currentHeading != null || paragraphs.isNotEmpty()) sections.add(NoteSection(currentHeading, paragraphs.toList()))
            currentHeading = null
            paragraphs.clear()
        }
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            if (line.contains('[') && !line.contains(']') && i + 1 < lines.size && lines[i + 1].contains(']')) {
                val combined = "$line ${lines[i + 1]}"
                if (heading.matches(combined)) { line = combined; i++ }
            }
            when {
                line.isEmpty() -> flushParagraph()
                heading.matches(line) -> { flushSection(); currentHeading = line }
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
}
