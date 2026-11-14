package de.pflegeexamen.app.data.ingestion

import de.pflegeexamen.app.domain.model.RawPage
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Streaming Word parser: no unbounded decompressed XML buffer or in-memory DOM. */
object DocxParser {
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun parseToPages(inputStream: InputStream): List<RawPage> {
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            var entries = 0
            var totalOtherBytes = 0L
            while (entry != null) {
                if (++entries > ImportLimits.MAX_BUNDLE_ENTRIES) ImportLimits.tooLarge("Das Word-Dokument")
                if (entry.name == "word/document.xml") {
                    val handler = DocumentHandler()
                    val factory = SAXParserFactory.newInstance().apply {
                        isNamespaceAware = true
                        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
                        try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) { }
                        try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) { }
                    }
                    val reader = factory.newSAXParser().xmlReader
                    reader.contentHandler = handler
                    reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                        throw SAXException("Externe XML-Entitäten sind nicht erlaubt.")
                    }
                    reader.parse(InputSource(ImportLimits.bounded(zip, ImportLimits.MAX_XML_BYTES, "Das entpackte Word-Dokument")))
                    return handler.finish()
                }
                val bounded = ImportLimits.bounded(zip, ImportLimits.MAX_XML_BYTES - totalOtherBytes, "Das Word-Dokument")
                val buffer = ByteArray(8192)
                while (true) {
                    val n = bounded.read(buffer)
                    if (n < 0) break
                    totalOtherBytes += n
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("Keine gültige Word-Datei (.docx): word/document.xml fehlt.")
    }

    private class DocumentHandler : DefaultHandler() {
        private val pages = mutableListOf<RawPage>()
        private val page = StringBuilder()
        private val paragraph = StringBuilder()
        private val cells = mutableListOf<String>()
        private val cell = StringBuilder()
        private var inText = false
        private var inParagraph = false
        private var inCell = false
        private var inRow = false
        private var heading = false
        private var bullet = false
        private var totalChars = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
            when (localName) {
                "tr" -> { inRow = true; cells.clear() }
                "tc" -> if (inRow) { inCell = true; cell.clear() }
                "p" -> { inParagraph = true; paragraph.clear(); heading = false; bullet = false }
                "pStyle" -> if (inParagraph) {
                    val value = attrs.getValue(WORD_NS, "val") ?: attrs.getValue("w:val") ?: ""
                    heading = value.contains("Heading", true) || value.contains("berschrift", true) || value.contains("Title", true)
                }
                "numPr" -> if (inParagraph) bullet = true
                "t" -> if (inParagraph) inText = true
                "tab" -> if (inParagraph) paragraph.append(' ')
                "br", "cr" -> if (inParagraph) paragraph.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText && inParagraph) {
                totalChars += length
                if (totalChars > ImportLimits.MAX_TEXT_CHARS || paragraph.length + length > ImportLimits.MAX_PAGE_CHARS) {
                    ImportLimits.tooLarge("Der Word-Text")
                }
                paragraph.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName) {
                "t" -> inText = false
                "p" -> {
                    inParagraph = false
                    val text = paragraph.toString().trim()
                    if (text.isNotBlank()) {
                        if (inCell) {
                            if (cell.isNotEmpty()) cell.append("; ")
                            cell.append(text)
                        } else {
                            appendBlock(when {
                                heading -> "# $text"
                                bullet && !text.startsWith("•") && !text.startsWith("-") -> "• $text"
                                else -> text
                            })
                        }
                    }
                }
                "tc" -> { if (inCell && cell.isNotBlank()) cells.add(cell.toString()); inCell = false }
                "tr" -> { if (inRow && cells.isNotEmpty()) appendBlock("• ${cells.joinToString(": ")}"); inRow = false }
            }
        }

        private fun appendBlock(block: String) {
            // A paragraph can itself be very long; split it without dropping content.
            var offset = 0
            while (offset < block.length) {
                if (page.isNotEmpty()) {
                    if (page.length + 2 >= 3500) flush() else page.append("\n\n")
                }
                val end = (offset + (3500 - page.length)).coerceAtMost(block.length)
                page.append(block, offset, end)
                offset = end
                if (offset < block.length) flush()
            }
        }

        private fun flush() {
            val text = page.toString().trim()
            if (text.isNotBlank()) pages.add(RawPage(pageNumber = pages.size + 1, text = text))
            page.clear()
        }

        fun finish(): List<RawPage> { flush(); return pages }
    }
}
