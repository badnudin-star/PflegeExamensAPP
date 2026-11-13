package de.pflegeexamen.app.data.ingestion

import de.pflegeexamen.app.domain.model.RawPage
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reiner Kotlin/JDK-Parser für Word-Dateien (.docx) ohne schwere Drittanbieter-Bibliotheken.
 * Extrahiert Absätze, Überschriften, Listenpunkte und Tabellenzeilen aus word/document.xml.
 */
object DocxParser {

    fun parseToPages(inputStream: InputStream): List<RawPage> {
        var documentXmlBytes: ByteArray? = null
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXmlBytes = zip.readBytes()
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val xmlBytes = documentXmlBytes
            ?: throw IllegalArgumentException("Keine gültige Word-Datei (.docx): word/document.xml fehlt.")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
                // Nicht auf jeder Android-XML-Implementierung unterstützt
            }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        val bodyList = doc.getElementsByTagNameNS("*", "body")
        if (bodyList.length == 0) return emptyList()

        val body = bodyList.item(0)
        val lines = mutableListOf<String>()

        val children = body.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            when (elem.localName) {
                "p" -> extractParagraph(elem)?.let { lines.add(it) }
                "tbl" -> extractTable(elem, lines)
            }
        }

        val fullText = lines.joinToString("\n\n").trim()
        if (fullText.isBlank()) return emptyList()

        // Teile sehr lange DOCX-Dokumente in logische Abschnitte (~3500 Zeichen pro virtuellem Abschnitt)
        val pages = mutableListOf<RawPage>()
        val buffer = StringBuilder()
        var sectionNumber = 1
        for (block in lines) {
            if (buffer.isNotEmpty() && buffer.length + block.length > 3500) {
                pages.add(RawPage(pageNumber = sectionNumber++, text = buffer.toString().trim()))
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(block)
        }
        if (buffer.isNotEmpty()) {
            pages.add(RawPage(pageNumber = sectionNumber, text = buffer.toString().trim()))
        }
        return pages
    }

    private fun extractParagraph(p: Element): String? {
        val textSb = StringBuilder()
        var isHeading = false
        var isBullet = false

        val pChildren = p.childNodes
        for (i in 0 until pChildren.length) {
            val child = pChildren.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val childElem = child as Element
            when (childElem.localName) {
                "pPr" -> {
                    val pStyles = childElem.getElementsByTagNameNS("*", "pStyle")
                    if (pStyles.length > 0) {
                        val styleElem = pStyles.item(0) as Element
                        val styleVal = styleElem
                            .getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")
                            .ifBlank { styleElem.getAttribute("w:val") }
                            .ifBlank { styleElem.getAttribute("val") }
                        if (styleVal.contains("Heading", ignoreCase = true) ||
                            styleVal.contains("berschrift", ignoreCase = true) ||
                            styleVal.contains("Title", ignoreCase = true)
                        ) {
                            isHeading = true
                        }
                    }
                    if (childElem.getElementsByTagNameNS("*", "numPr").length > 0) {
                        isBullet = true
                    }
                }
                "r" -> collectRunText(childElem, textSb)
                "hyperlink" -> {
                    val runs = childElem.getElementsByTagNameNS("*", "r")
                    for (rIdx in 0 until runs.length) {
                        collectRunText(runs.item(rIdx) as Element, textSb)
                    }
                }
            }
        }

        val raw = textSb.toString().trim()
        if (raw.isBlank()) return null
        return when {
            isHeading -> "# $raw"
            isBullet && !raw.startsWith("•") && !raw.startsWith("-") -> "• $raw"
            else -> raw
        }
    }

    private fun collectRunText(run: Element, out: StringBuilder) {
        val children = run.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.localName) {
                "t" -> out.append(el.textContent)
                "tab" -> out.append(' ')
                "br", "cr" -> out.append('\n')
            }
        }
    }

    private fun extractTable(tbl: Element, outLines: MutableList<String>) {
        val rows = tbl.getElementsByTagNameNS("*", "tr")
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val cellTexts = mutableListOf<String>()
            val cells = row.childNodes
            for (c in 0 until cells.length) {
                val cellNode = cells.item(c)
                if (cellNode.nodeType != Node.ELEMENT_NODE) continue
                val cellElem = cellNode as Element
                if (cellElem.localName != "tc") continue
                val paras = cellElem.getElementsByTagNameNS("*", "p")
                val parts = mutableListOf<String>()
                for (p in 0 until paras.length) {
                    extractParagraph(paras.item(p) as Element)?.let { parts.add(it.removePrefix("# ").trim()) }
                }
                val joined = parts.joinToString("; ").trim()
                if (joined.isNotBlank()) cellTexts.add(joined)
            }
            if (cellTexts.isNotEmpty()) {
                outLines.add("• ${cellTexts.joinToString(": ")}")
            }
        }
    }
}
