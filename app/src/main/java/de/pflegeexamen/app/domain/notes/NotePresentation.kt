package de.pflegeexamen.app.domain.notes

/** Display-only transformation. The stored source text is never modified. */
data class NoteSection(val heading: String?, val paragraphs: List<String>)
data class NoteBlock(val text: String, val isHeading: Boolean = false)
/** Indices and offsets refer to displayed text, not source PDF pages. */
data class NoteHeading(val blockIndex: Int, val text: String)
data class NoteMatch(val blockIndex: Int, val offset: Int)

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
            if (modern && line.matches(Regex("(?i)^QUELLEN(?: UND .{1,80})?$"))) { flushSection(); skipSources = true; i++; continue }
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

    /** Complete extracted text, without removing headers, sources or table rows.
     *  Group lines to avoid laying out an entire long document as one Text node.
     *  This is not a PDF renderer: images and extraction errors are not recovered.
     */
    fun fullTextBlocks(raw: String): List<NoteBlock> = if (raw.isEmpty()) emptyList() else
        raw.split('\n').chunked(30).map { NoteBlock(it.joinToString("\n")) }
    /** Use the already displayed headings; preserve order and duplicate labels. */
    fun headings(blocks: List<NoteBlock>): List<NoteHeading> = blocks.mapIndexedNotNull { index, block ->
        if (block.isHeading && block.text.isNotBlank()) NoteHeading(index, block.text) else null
    }

    fun firstMatch(blocks: List<NoteBlock>, query: String): Int? {
        val term = query.trim()
        if (term.isEmpty()) return null
        return blocks.indexOfFirst { it.text.contains(term, ignoreCase = true) }.takeIf { it >= 0 }
    }

    /** Non-overlapping matches use the same order and boundaries as the visual highlighting. */
    fun nextMatch(blocks: List<NoteBlock>, query: String, after: NoteMatch? = null): NoteMatch? {
        val term = query.trim()
        if (term.isEmpty() || (after != null && !isMatch(blocks, term, after))) return null
        val startBlock = after?.blockIndex ?: 0
        for (index in startBlock until blocks.size) {
            val start = if (after != null && index == after.blockIndex) after.offset + term.length else 0
            val found = blocks[index].text.indexOf(term, start, ignoreCase = true)
            if (found >= 0) return NoteMatch(index, found)
        }
        return null
    }

    fun previousMatch(blocks: List<NoteBlock>, query: String, before: NoteMatch): NoteMatch? {
        val term = query.trim()
        if (term.isEmpty() || !isMatch(blocks, term, before)) return null
        // Scan forward within a block so overlapping substrings agree with the highlighting.
        for (index in before.blockIndex downTo 0) {
            val text = blocks[index].text
            val end = if (index == before.blockIndex) before.offset else text.length
            var start = 0
            var last = -1
            while (start <= end) {
                val found = text.indexOf(term, start, ignoreCase = true)
                if (found < 0 || found >= end) break
                last = found
                start = found + term.length
            }
            if (last >= 0) return NoteMatch(index, last)
        }
        return null
    }

    fun isMatch(blocks: List<NoteBlock>, query: String, match: NoteMatch): Boolean {
        val term = query.trim()
        val text = blocks.getOrNull(match.blockIndex)?.text ?: return false
        if (term.isEmpty() || match.offset < 0 || match.offset > text.length - term.length) return false
        var start = 0
        while (start <= match.offset) {
            val found = text.indexOf(term, start, ignoreCase = true)
            if (found == match.offset) return true
            if (found < 0 || found > match.offset) return false
            start = found + term.length
        }
        return false
    }
}
