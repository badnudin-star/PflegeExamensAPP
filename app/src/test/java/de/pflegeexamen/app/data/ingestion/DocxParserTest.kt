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
}
