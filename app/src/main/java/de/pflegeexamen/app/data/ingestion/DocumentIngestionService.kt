package de.pflegeexamen.app.data.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.Writer
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
        if (bytes.size.toLong() > ImportLimits.MAX_DOCUMENT_BYTES) ImportLimits.tooLarge("Die Datei")
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
                if (bytes.size.toLong() > ImportLimits.MAX_TEXT_BYTES) ImportLimits.tooLarge("Die Textdatei")
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
                if (bytes.size.toLong() > ImportLimits.MAX_TEXT_BYTES) ImportLimits.tooLarge("Die Textdatei")
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

    /** PDFBox uses a disk-backed scratch file rather than buffering the complete PDF in RAM. */
    private suspend fun importPdfFromFile(file: File, fileName: String): ParsedImportResult {
        if (file.length() > ImportLimits.MAX_DOCUMENT_BYTES) ImportLimits.tooLarge("Das PDF")
        return extractPdf(fileName, { settings -> PDDocument.load(file, settings) }) {
            runPdfRendererOcrFromPfd(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }

    private suspend fun importPdf(uri: Uri, fileName: String): ParsedImportResult {
        // Read the provider through a bounded stream. Scratch storage is private to the app;
        // the original URI is never opened for writing.
        return extractPdf(fileName, { settings ->
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("PDF-Datei konnte nicht geöffnet werden.")
            input.use { PDDocument.load(ImportLimits.bounded(it, ImportLimits.MAX_DOCUMENT_BYTES, "Das PDF"), settings) }
        }) { runPdfRendererOcrFallback(uri) }
    }

    private suspend fun extractPdf(
        fileName: String,
        load: (MemoryUsageSetting) -> PDDocument,
        ocr: suspend () -> List<RawPage>
    ): ParsedImportResult {
        val pages = mutableListOf<RawPage>()
        var totalChars = 0
        val context = currentCoroutineContext()
        val pageWriter = object : Writer() {
            val buffer = StringBuilder()
            override fun write(chars: CharArray, offset: Int, length: Int) {
                context.ensureActive()
                if (buffer.length + length > ImportLimits.MAX_PAGE_CHARS || totalChars.toLong() + length > ImportLimits.MAX_TEXT_CHARS) {
                    ImportLimits.tooLarge("Der extrahierte PDF-Text")
                }
                buffer.append(chars, offset, length)
                totalChars += length
            }
            fun finishPage(number: Int) {
                val text = buffer.toString().trim()
                if (text.isNotEmpty()) pages.add(RawPage(pageNumber = number, text = text))
                buffer.clear()
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val settings = MemoryUsageSetting.setupTempFileOnly().setTempDir(contextCacheDir())
        val pageCount = load(settings).use { document ->
            val stripper = object : PDFTextStripper() {
                var pageGlyphs = 0
                override fun startPage(page: PDPage) {
                    context.ensureActive()
                    pageGlyphs = 0
                }
                override fun processTextPosition(text: TextPosition) {
                    if (++pageGlyphs > 150_000) ImportLimits.tooLarge("Eine PDF-Seite")
                    if (pageGlyphs % 512 == 0) context.ensureActive()
                    super.processTextPosition(text)
                }
                override fun endPage(page: PDPage) {
                    pageWriter.finishPage(getCurrentPageNo())
                }
            }.apply { sortByPosition = true }
            // One traversal, rather than re-walking the complete page tree for each page.
            stripper.writeText(document, pageWriter)
            document.numberOfPages
        }
        val avgChars = if (pageCount > 0) totalChars / pageCount else 0
        if (pageCount > 0 && avgChars < 35) {
            // Do not silently import only the first 40 scanned pages.
            if (pageCount > ImportLimits.MAX_OCR_PAGES) {
                throw IllegalArgumentException("Dieses gescannte PDF hat $pageCount Seiten. Bitte für die Texterkennung in Dateien mit höchstens ${ImportLimits.MAX_OCR_PAGES} Seiten aufteilen; die Originaldatei bleibt unverändert.")
            }
            val ocrPages = ocr()
            if (ocrPages.sumOf { it.text.length } > totalChars) {
                return ParsedImportResult(fileName.substringBeforeLast('.'), fileName, SourceType.PDF, ocrPages, true)
            }
        }
        return ParsedImportResult(fileName.substringBeforeLast('.'), fileName, SourceType.PDF, pages)
    }

    private fun contextCacheDir(): File = context.cacheDir

    private suspend fun runPdfRendererOcrFallback(uri: Uri): List<RawPage> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return runPdfRendererOcrFromPfd(pfd)
    }

    private suspend fun runPdfRendererOcrFromPfd(pfd: ParcelFileDescriptor): List<RawPage> {
        val ocrPages = mutableListOf<RawPage>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val maxPages = renderer.pageCount
                if (maxPages > ImportLimits.MAX_OCR_PAGES) ImportLimits.tooLarge("Das gescannte PDF")
                for (index in 0 until maxPages) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val scale = 2
                        val width = (page.width * scale).coerceIn(600, 1800)
                        val height = (page.height * scale).coerceIn(800, 2400)
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
        val bytes = context.contentResolver.openInputStream(uri)?.use { ImportLimits.readBounded(it, ImportLimits.MAX_TEXT_BYTES, "Die Textdatei") }
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
        if (raw.length > ImportLimits.MAX_TEXT_CHARS) ImportLimits.tooLarge("Der Text")
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.length <= 3600) return listOf(RawPage(pageNumber = 1, text = trimmed))

        val pages = mutableListOf<RawPage>()
        val paragraphs = trimmed.split(Regex("""\n\s*\n+"""))
        val buffer = StringBuilder()
        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) pages.add(RawPage(pageNumber = pages.size + 1, text = text))
            buffer.clear()
        }
        for (paragraph in paragraphs) {
            var offset = 0
            if (buffer.isNotEmpty() && buffer.length + paragraph.length + 2 > 3500) flush()
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            while (offset < paragraph.length) {
                val end = minOf(offset + (3500 - buffer.length), paragraph.length)
                buffer.append(paragraph, offset, end)
                offset = end
                if (offset < paragraph.length) flush()
            }
        }
        flush()
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
        context.assets.open(assetPath).use { ImportLimits.readBounded(it, ImportLimits.MAX_BUNDLE_BYTES, "Die Asset-Datei") }
    }.getOrNull()
}
