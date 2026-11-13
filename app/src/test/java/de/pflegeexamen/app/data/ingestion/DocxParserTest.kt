package de.pflegeexamen.app.data.ingestion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {

    @Test
    fun `parseToPages extracts headings bullets and tables from valid docx zip`() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Pneumonieprophylaxe</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr>
                  <w:r><w:t>Atemstimulierende Einreibung durchführen</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Maßnahme</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Frühmobilisation</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val pages = DocxParser.parseToPages(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(pages.isNotEmpty())
        val joined = pages.joinToString("\n") { it.text }
        assertTrue(joined.contains("# Pneumonieprophylaxe"))
        assertTrue(joined.contains("• Atemstimulierende Einreibung durchführen"))
        assertTrue(joined.contains("Maßnahme: Frühmobilisation"))
    }

    @Test
    fun `parseToPages detects heading style even with custom xml namespace prefix`() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <ns0:document xmlns:ns0="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <ns0:body>
                <ns0:p>
                  <ns0:pPr><ns0:pStyle ns0:val="Überschrift1"/></ns0:pPr>
                  <ns0:r><ns0:t>Herzinsuffizienz</ns0:t></ns0:r>
                </ns0:p>
                <ns0:p>
                  <ns0:r><ns0:t>Regelmäßige Gewichtskontrolle durchführen.</ns0:t></ns0:r>
                </ns0:p>
              </ns0:body>
            </ns0:document>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val pages = DocxParser.parseToPages(ByteArrayInputStream(baos.toByteArray()))
        val joined = pages.joinToString("\n") { it.text }
        assertTrue(joined.contains("# Herzinsuffizienz"))
    }
}
