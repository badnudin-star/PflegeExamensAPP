package de.pflegeexamen.app.data.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class ParsedImportResult(
    val suggestedTitle: String,
    val originalFileName: String?,
    val sourceType: SourceType,
    val pages: List<RawPage>,
    val usedOcrFallbackForPdf: Boolean = false
)

/**
 * Zentrale Offline-Import-Pipeline für PDF (inkl. automatischem OCR-Fallback für gescannte PDFs),
 * Word (.docx), Text (.txt), Markdown (.md) und Buchseiten-Fotos (OCR).
 */
class DocumentIngestionService(
    private val context: Context,
    private val ocrProcessor: OcrProcessor
) {

    suspend fun importDocumentFromUri(uri: Uri): ParsedImportResult = withContext(Dispatchers.IO) {
        val fileName = queryFileName(uri) ?: "Importierte Quelle"
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
        val lowerName = fileName.lowercase()

        when {
            lowerName.endsWith(".pdf") || mimeType == "application/pdf" -> {
                importPdf(uri, fileName)
            }
            lowerName.endsWith(".docx") || mimeType.contains("wordprocessingml") -> {
                val pages = context.contentResolver.openInputStream(uri)?.use { stream ->
                    DocxParser.parseToPages(stream)
                } ?: throw IllegalArgumentException("Word-Datei konnte nicht geöffnet werden.")
                ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.DOCX,
                    pages = pages
                )
            }
            lowerName.endsWith(".md") || mimeType == "text/markdown" -> {
                val raw = readTextSmart(uri)
                ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.MD,
                    pages = splitPlainTextToLogicalSections(raw)
                )
            }
            mimeType.startsWith("image/") ||
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> {
                val recognized = ocrProcessor.recognizeFromUri(uri)
                ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.IMAGE_OCR,
                    pages = listOf(RawPage(pageNumber = 1, text = recognized))
                )
            }
            else -> {
                val raw = readTextSmart(uri)
                ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.TXT,
                    pages = splitPlainTextToLogicalSections(raw)
                )
            }
        }
    }

    suspend fun importDocumentFromBytes(
        fileName: String,
        bytes: ByteArray
    ): ParsedImportResult = withContext(Dispatchers.IO) {
        val cleanName = fileName.substringAfterLast('/').ifBlank { "Quelle.txt" }
        val lowerName = cleanName.lowercase()

        when {
            lowerName.endsWith(".pdf") -> {
                val tempFile = File.createTempFile("seed_", ".pdf", context.cacheDir)
                try {
                    tempFile.writeBytes(bytes)
                    importPdfFromFile(tempFile, cleanName)
                } finally {
                    tempFile.delete()
                }
            }
            lowerName.endsWith(".docx") -> {
                val pages = ByteArrayInputStream(bytes).use { DocxParser.parseToPages(it) }
                ParsedImportResult(
                    suggestedTitle = cleanName.substringBeforeLast('.'),
                    originalFileName = cleanName,
                    sourceType = SourceType.DOCX,
                    pages = pages
                )
            }
            lowerName.endsWith(".md") -> {
                val raw = decodeSmartBytes(bytes)
                ParsedImportResult(
                    suggestedTitle = cleanName.substringBeforeLast('.'),
                    originalFileName = cleanName,
                    sourceType = SourceType.MD,
                    pages = splitPlainTextToLogicalSections(raw)
                )
            }
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> {
                val tempImg = File.createTempFile("seed_img_", ".${cleanName.substringAfterLast('.', "jpg")}", context.cacheDir)
                try {
                    tempImg.writeBytes(bytes)
                    val recognized = ocrProcessor.recognizeFromUri(Uri.fromFile(tempImg))
                    ParsedImportResult(
                        suggestedTitle = cleanName.substringBeforeLast('.'),
                        originalFileName = cleanName,
                        sourceType = SourceType.IMAGE_OCR,
                        pages = listOf(RawPage(pageNumber = 1, text = recognized))
                    )
                } finally {
                    tempImg.delete()
                }
            }
            else -> {
                val raw = decodeSmartBytes(bytes)
                ParsedImportResult(
                    suggestedTitle = cleanName.substringBeforeLast('.'),
                    originalFileName = cleanName,
                    sourceType = SourceType.TXT,
                    pages = splitPlainTextToLogicalSections(raw)
                )
            }
        }
    }

    private suspend fun importPdfFromFile(file: File, fileName: String): ParsedImportResult {
        val pages = mutableListOf<RawPage>()
        var totalChars = 0
        var pageCount = 0

        file.inputStream().use { stream ->
            PDDocument.load(stream).use { document ->
                pageCount = document.numberOfPages
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 1..pageCount) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(document).trim()
                    totalChars += text.length
                    if (text.isNotBlank()) {
                        pages.add(RawPage(pageNumber = page, text = text))
                    }
                }
            }
        }

        val avgChars = if (pageCount > 0) totalChars / pageCount else 0
        if (pageCount > 0 && avgChars < 35) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val ocrPages = runPdfRendererOcrFromPfd(pfd)
            if (ocrPages.sumOf { it.text.length } > totalChars) {
                return ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.PDF,
                    pages = ocrPages,
                    usedOcrFallbackForPdf = true
                )
            }
        }

        return ParsedImportResult(
            suggestedTitle = fileName.substringBeforeLast('.'),
            originalFileName = fileName,
            sourceType = SourceType.PDF,
            pages = pages,
            usedOcrFallbackForPdf = false
        )
    }

    private suspend fun importPdf(uri: Uri, fileName: String): ParsedImportResult {
        val pages = mutableListOf<RawPage>()
        var totalChars = 0
        var pageCount = 0

        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { document ->
                pageCount = document.numberOfPages
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                for (page in 1..pageCount) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(document).trim()
                    totalChars += text.length
                    if (text.isNotBlank()) {
                        pages.add(RawPage(pageNumber = page, text = text))
                    }
                }
            }
        } ?: throw IllegalArgumentException("PDF-Datei konnte nicht geöffnet werden.")

        // Falls das PDF gescannte Seiten ohne eingebettete Text-Ebene enthält -> automatisch Offline-OCR nutzen!
        val avgChars = if (pageCount > 0) totalChars / pageCount else 0
        if (pageCount > 0 && avgChars < 35) {
            val ocrPages = runPdfRendererOcrFallback(uri)
            if (ocrPages.sumOf { it.text.length } > totalChars) {
                return ParsedImportResult(
                    suggestedTitle = fileName.substringBeforeLast('.'),
                    originalFileName = fileName,
                    sourceType = SourceType.PDF,
                    pages = ocrPages,
                    usedOcrFallbackForPdf = true
                )
            }
        }

        return ParsedImportResult(
            suggestedTitle = fileName.substringBeforeLast('.'),
            originalFileName = fileName,
            sourceType = SourceType.PDF,
            pages = pages,
            usedOcrFallbackForPdf = false
        )
    }

    private suspend fun runPdfRendererOcrFallback(uri: Uri): List<RawPage> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return runPdfRendererOcrFromPfd(pfd)
    }

    private suspend fun runPdfRendererOcrFromPfd(pfd: ParcelFileDescriptor): List<RawPage> {
        val ocrPages = mutableListOf<RawPage>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val maxPages = renderer.pageCount.coerceAtMost(40)
                for (index in 0 until maxPages) {
                    renderer.openPage(index).use { page ->
                        val scale = 2
                        val width = (page.width * scale).coerceIn(600, 2200)
                        val height = (page.height * scale).coerceIn(800, 3000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val recognized = ocrProcessor.recognizeFromBitmap(bitmap)
                            if (recognized.isNotBlank()) {
                                ocrPages.add(RawPage(pageNumber = index + 1, text = recognized))
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return ocrPages
    }

    private fun readTextSmart(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Textdatei konnte nicht gelesen werden.")
        return decodeSmartBytes(bytes)
    }

    private fun decodeSmartBytes(bytes: ByteArray): String {
        val utf8 = bytes.toString(StandardCharsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            bytes.toString(Charset.forName("ISO-8859-1"))
        } else {
            utf8
        }
    }

    fun splitPlainTextToLogicalSections(raw: String): List<RawPage> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.length <= 3600) return listOf(RawPage(pageNumber = 1, text = trimmed))

        val pages = mutableListOf<RawPage>()
        val paragraphs = trimmed.split(Regex("""\n\s*\n+"""))
        val buffer = StringBuilder()
        var sectionNum = 1
        for (p in paragraphs) {
            if (buffer.isNotEmpty() && buffer.length + p.length > 3500) {
                pages.add(RawPage(pageNumber = sectionNum++, text = buffer.toString().trim()))
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(p)
        }
        if (buffer.isNotEmpty()) {
            pages.add(RawPage(pageNumber = sectionNum, text = buffer.toString().trim()))
        }
        return pages
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    suspend fun recognizeTextFromImageUri(uri: Uri): String = withContext(Dispatchers.IO) {
        ocrProcessor.recognizeFromUri(uri)
    }

    fun openUriInputStream(uri: Uri) = context.contentResolver.openInputStream(uri)

    fun openUriOutputStream(uri: Uri) = context.contentResolver.openOutputStream(uri)

    fun readAssetBytesOrNull(assetPath: String): ByteArray? = runCatching {
        context.assets.open(assetPath).use { it.readBytes() }
    }.getOrNull()
}
