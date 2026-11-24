package de.pflegeexamen.app.data.progress

import org.junit.Assert.*
import org.junit.Test

class PreferenceXmlInspectorTest {
    private fun inspect(xml: String) = PreferenceXmlInspector.inspect(xml.toByteArray(Charsets.UTF_8))

    @Test fun `reads supported android preference values and preserves content`() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map><set name="learned_card_ids"><string>c&amp;1</string><string>🙂</string></set>
            <string name="2026-11-13">{&quot;value&quot;:1}</string></map>""".trimIndent()
        val map = mapOf("learned_card_ids" to setOf("c&1", "🙂"), "2026-11-13" to "{\"value\":1}")
        assertEquals(map, inspect(xml))
        assertTrue(PreferenceXmlInspector.matches(xml.toByteArray(), map))
        assertFalse(PreferenceXmlInspector.matches(xml.toByteArray(), mapOf("learned_card_ids" to emptySet<String>())))
        assertEquals(emptyMap<String, Any>(), inspect("<map/>"))
        assertEquals(mapOf("x" to "  text  ", "empty" to emptySet<String>()),
            inspect("<map><string name=\"x\">  text  </string><set name=\"empty\"/></map>"))
    }

    @Test fun `malformed duplicate and unsupported preference xml fails closed`() {
        val bad = listOf(
            "", "<map><string name=\"x\">broken", "<map></map><map/>",
            "<other/>", "<map unexpected=\"x\"/>", "<map>unexpected</map>",
            "<map><int name=\"n\" value=\"1\"/></map>", "<map><string>missing name</string></map>",
            "<map><string name=\"x\">a</string><string name=\"x\">b</string></map>",
            "<map><set name=\"x\"><string>a</string><string>a</string></set></map>",
            "<map><set name=\"x\"><string name=\"y\">a</string></set></map>",
            "<map><set name=\"x\"><set/></set></map>", "<map><?instruction value?></map>",
            "<!DOCTYPE map [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><map><string name=\"x\">&e;</string></map>",
            "<!DOCTYPE map [<!ENTITY e 'expanded'>]><map><string name=\"x\">&e;</string></map>"
        )
        bad.forEach { assertNull("accepted: $it", inspect(it)) }
        assertNull(PreferenceXmlInspector.inspect(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(PreferenceXmlInspector.inspect(ByteArray(4 * 1024 * 1024 + 1)))
    }
}
