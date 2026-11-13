package de.pflegeexamen.app.data.bundle

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BundleManifestTest {

    @Test
    fun `bundle manifest serializes and deserializes cleanly`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val manifest = BundleManifest(
            bundleVersion = 1,
            bundleName = "Examen 2027 Test",
            replaceExistingSources = true,
            dailyGoalPoints = 25,
            categories = listOf(
                BundleCategoryItem(name = "Tag 1", shortCode = "Tag 1", colorHex = 0xFF0E6B6EL)
            ),
            preparations = listOf(
                BundlePreparationItem(
                    title = "Herzinsuffizienz",
                    examDayTag = "Tag 1",
                    competenceFocusCodes = "I.1, I.5",
                    focusParametersCsv = "Anatomie & Physiologie, Symptome & Beobachtung"
                )
            ),
            operators = listOf(
                BundleOperatorItem(
                    name = "Definieren",
                    requirementLevel = "Taxonomie WV",
                    caseRelationRule = "NO_CASE_RELATION",
                    gradingRubric = "Pro Definition 2 Punkte.",
                    defaultPoints = 2
                )
            ),
            sources = listOf(
                BundleSourceItem(
                    fileName = "sources/herz.pdf",
                    title = "Herzinsuffizienz Skript",
                    categoryShortCode = "Tag 1",
                    tags = "Anatomie, Herz",
                    topicTitles = listOf("Herzinsuffizienz", "Thrombose")
                )
            )
        )

        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<BundleManifest>(encoded)

        assertEquals("Examen 2027 Test", decoded.bundleName)
        assertEquals(true, decoded.replaceExistingSources)
        assertEquals(false, json.decodeFromString<BundleManifest>("{}").replaceExistingSources)
        assertEquals(25, decoded.dailyGoalPoints)
        assertEquals("Tag 1", decoded.preparations.first().examDayTag)
        assertEquals("NO_CASE_RELATION", decoded.operators.first().caseRelationRule)
        assertEquals("sources/herz.pdf", decoded.sources.first().fileName)
        assertEquals(listOf("Herzinsuffizienz", "Thrombose"), decoded.sources.first().topicTitles)
        assertEquals(null, json.decodeFromString<BundleSourceItem>("{\"title\":\"Alt\"}").topicTitles)
    }
}
