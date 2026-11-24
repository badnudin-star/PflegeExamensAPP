package de.pflegeexamen.app.data.progress

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Narrow, fail-closed reader for the UTF-8 subset emitted by Android for the preference
 * string and string-set types. It is not a general XML parser. DTDs, custom entities,
 * comments, namespaces and unsupported types are rejected rather than interpreted.
 * Supplied bytes only: no files, .bak resolution, locking, backup or restore.
 */
internal object PreferenceXmlInspector {
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private val declaration = Regex(
        "<\\?xml\\s+version\\s*=\\s*(?:'1\\.0'|\"1\\.0\")" +
            "(?:\\s+encoding\\s*=\\s*(?:'(?i:utf-8)'|\"(?i:utf-8)\"))?" +
            "(?:\\s+standalone\\s*=\\s*(?:'yes'|\"yes\"|'no'|\"no\"))?\\s*\\?>"
    )

    fun inspect(bytes: ByteArray): Map<String, Any>? {
        if (bytes.size > MAX_XML_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .replace("\r\n", "\n").replace('\r', '\n')
            Parser(text).parse()
        } catch (_: Exception) {
            null
        }
    }

    /** Equality with one supplied map is not proof of a consistent or durable snapshot. */
    fun matches(bytes: ByteArray, memory: Map<String, *>): Boolean = inspect(bytes)?.let { it == memory } == true

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Map<String, Any> {
            if (text.startsWith('\uFEFF')) pos++
            if (text.startsWith("<?xml", pos)) {
                val match = declaration.find(text, pos)
                require(match != null && match.range.first == pos)
                pos = match.range.last + 1
            }
            whitespace()
            literal("<map")
            val result = linkedMapOf<String, Any>()
            if (!tagEnd()) {
                while (true) {
                    whitespace()
                    if (text.startsWith("</map>", pos)) { pos += 6; break }
                    val isSet = when {
                        text.startsWith("<set", pos) -> { pos += 4; true }
                        text.startsWith("<string", pos) -> { pos += 7; false }
                        else -> throw IllegalArgumentException("Unsupported entry")
                    }
                    require(whitespace())
                    literal("name")
                    whitespace(); literal("="); whitespace()
                    val quote = take()
                    require(quote == '\'' || quote == '"')
                    val name = value(quote, attribute = true)
                    literal(quote.toString())
                    require(name.isNotEmpty() && name !in result)
                    val empty = tagEnd()
                    if (isSet) {
                        val values = linkedSetOf<String>()
                        if (!empty) {
                            while (true) {
                                whitespace()
                                if (text.startsWith("</set>", pos)) { pos += 6; break }
                                literal("<string")
                                if (tagEnd()) {
                                    require(values.add(""))
                                } else {
                                    val item = value('<', attribute = false)
                                    literal("</string>")
                                    require(values.add(item))
                                }
                            }
                        }
                        result[name] = values.toSet()
                    } else if (empty) {
                        result[name] = ""
                    } else {
                        val item = value('<', attribute = false)
                        literal("</string>")
                        result[name] = item
                    }
                }
            }
            whitespace()
            require(pos == text.length)
            return result.toMap()
        }

        private fun tagEnd(): Boolean {
            whitespace()
            return when {
                text.startsWith("/>", pos) -> { pos += 2; true }
                text.startsWith(">", pos) -> { pos++; false }
                else -> throw IllegalArgumentException("Unexpected tag")
            }
        }

        private fun value(end: Char, attribute: Boolean): String {
            val result = StringBuilder()
            while (pos < text.length && text[pos] != end) {
                if (text.startsWith("]]>", pos) && !attribute) throw IllegalArgumentException("Invalid text")
                when (val ch = take()) {
                    '<' -> throw IllegalArgumentException("Unexpected markup")
                    '&' -> {
                        val semicolon = text.indexOf(';', pos)
                        require(semicolon >= pos && semicolon - pos <= 12)
                        val entity = text.substring(pos, semicolon)
                        pos = semicolon + 1
                        val code = when (entity) {
                            "amp" -> '&'.code
                            "lt" -> '<'.code
                            "gt" -> '>'.code
                            "quot" -> '"'.code
                            "apos" -> '\''.code
                            else -> when {
                                entity.startsWith("#x") -> entity.substring(2).takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }?.toIntOrNull(16)
                                entity.startsWith("#") -> entity.substring(1).takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
                                else -> null
                            } ?: throw IllegalArgumentException("Unsupported entity")
                        }
                        require(xmlCodePoint(code))
                        result.appendCodePoint(code)
                    }
                    else -> {
                        val code = if (Character.isHighSurrogate(ch)) {
                            val low = take()
                            require(Character.isLowSurrogate(low))
                            Character.toCodePoint(ch, low)
                        } else {
                            require(!Character.isLowSurrogate(ch))
                            ch.code
                        }
                        require(xmlCodePoint(code))
                        if (attribute && (code == 9 || code == 10 || code == 13)) result.append(' ')
                        else result.appendCodePoint(code)
                    }
                }
            }
            require(pos < text.length)
            return result.toString()
        }

        private fun xmlCodePoint(code: Int) = code == 9 || code == 10 || code == 13 ||
            code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
        private fun whitespace(): Boolean {
            val start = pos
            while (pos < text.length && text[pos] in " \t\n\r") pos++
            return pos > start
        }
        private fun literal(value: String) { require(text.startsWith(value, pos)); pos += value.length }
        private fun take(): Char { require(pos < text.length); return text[pos++] }
    }
}
