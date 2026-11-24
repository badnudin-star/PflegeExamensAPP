package de.pflegeexamen.app.data.progress

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/**
 * Experimental inspection of the subset of Android preference XML used for practice
 * progress. Only supplied bytes are read: this does not open files, resolve .bak files,
 * synchronize with writers, prove durability, or implement backup/restore.
 * Unsupported XML, duplicate entries, parser failures, and DTDs are rejected.
 */
internal object PreferenceXmlInspector {
    private const val MAX_XML_BYTES = 4 * 1024 * 1024

    fun inspect(bytes: ByteArray): Map<String, Any>? {
        if (bytes.size > MAX_XML_BYTES) return null
        return try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                // Fail closed if any of these protections is unavailable.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val handler = Handler()
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
            handler.result()
        } catch (_: Exception) {
            null
        }
    }

    /** Equality with one supplied map is not proof of a consistent or durable snapshot. */
    fun matches(bytes: ByteArray, memory: Map<String, *>): Boolean = inspect(bytes)?.let { it == memory } == true

    private class Handler : DefaultHandler() {
        private val values = linkedMapOf<String, Any>()
        private var depth = 0
        private var finished = false
        private var entryName: String? = null
        private var set: MutableSet<String>? = null
        private var text: StringBuilder? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (depth) {
                0 -> requireXml(!finished && qName == "map" && attrs.length == 0)
                1 -> {
                    requireXml((qName == "set" || qName == "string") && attrs.length == 1 && attrs.getQName(0) == "name")
                    val name = attrs.getValue(0)
                    requireXml(name.isNotEmpty() && name !in values)
                    entryName = name
                    if (qName == "set") set = linkedSetOf() else text = StringBuilder()
                }
                2 -> {
                    requireXml(set != null && qName == "string" && attrs.length == 0 && text == null)
                    text = StringBuilder()
                }
                else -> throw SAXException("Unexpected nesting")
            }
            depth++
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val buffer = text
            if (buffer != null) buffer.append(ch, start, length)
            else for (i in start until start + length) requireXml(ch[i] == ' ' || ch[i] == '\n' || ch[i] == '\r' || ch[i] == '\t')
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (depth) {
                3 -> {
                    requireXml(qName == "string" && text != null && set != null)
                    requireXml(set!!.add(text.toString()))
                    text = null
                }
                2 -> {
                    val name = entryName ?: throw SAXException("Missing name")
                    if (qName == "set") {
                        requireXml(set != null && text == null)
                        values[name] = set!!.toSet()
                        set = null
                    } else {
                        requireXml(qName == "string" && text != null && set == null)
                        values[name] = text.toString()
                        text = null
                    }
                    entryName = null
                }
                1 -> { requireXml(qName == "map"); finished = true }
                else -> throw SAXException("Unexpected closing tag")
            }
            depth--
        }

        override fun processingInstruction(target: String?, data: String?) { throw SAXException("Processing instruction") }
        override fun resolveEntity(publicId: String?, systemId: String?) = throw SAXException("External entity")
        override fun warning(e: SAXParseException) { throw e }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
        fun result(): Map<String, Any> {
            requireXml(finished && depth == 0)
            return values.toMap()
        }
        private fun requireXml(condition: Boolean) { if (!condition) throw SAXException("Unsupported preference XML") }
    }
}
