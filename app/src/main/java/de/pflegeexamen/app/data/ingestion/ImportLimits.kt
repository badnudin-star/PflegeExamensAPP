package de.pflegeexamen.app.data.ingestion

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Limits are explicit: an oversized import fails without persisting a partial source. */
object ImportLimits {
    const val MAX_DOCUMENT_BYTES = 256L * 1024 * 1024
    const val MAX_TEXT_BYTES = 12L * 1024 * 1024
    const val MAX_XML_BYTES = 64L * 1024 * 1024
    const val MAX_TEXT_CHARS = 8_000_000
    const val MAX_PAGE_CHARS = 1_000_000
    const val MAX_OCR_PAGES = 40
    const val MAX_BUNDLE_BYTES = 64L * 1024 * 1024
    const val MAX_BUNDLE_ENTRIES = 500

    fun tooLarge(description: String): Nothing = throw IllegalArgumentException(
        "$description ist für die Verarbeitung auf diesem Gerät zu groß. Bitte die Datei in kleinere Teile aufteilen. Die Originaldatei bleibt unverändert."
    )

    fun readBounded(input: InputStream, maxBytes: Long, description: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) tooLarge(description)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Caps decompressed data independently of the (potentially forged) ZIP entry size. */
    fun bounded(input: InputStream, maxBytes: Long, description: String): InputStream =
        object : FilterInputStream(input) {
            private var total = 0L
            private fun count(bytes: Int) {
                if (bytes > 0) {
                    total += bytes
                    if (total > maxBytes) tooLarge(description)
                }
            }
            override fun read(): Int {
                val value = `in`.read()
                if (value >= 0) count(1)
                return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = `in`.read(b, off, len)
                count(n)
                return n
            }
            override fun skip(n: Long): Long {
                // Never allow skipping to bypass the decompressed size limit.
                val buffer = ByteArray(8192)
                var remaining = n
                while (remaining > 0) {
                    val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) break
                    remaining -= read
                }
                return n - remaining
            }
            override fun close() { /* owner closes the underlying ZIP stream */ }
        }
}
