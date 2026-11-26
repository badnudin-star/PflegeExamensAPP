package de.pflegeexamen.app.ui.navigation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.pflegeexamen.app.ui.theme.PflegeExamenTheme
import org.junit.Rule
import org.junit.Test

/** The debug APK contains a two-page blank synthetic PDF; no Drive data is used. */
class NotePdfUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun matchingPdfRendersBothPages() {
        compose.setContent { PflegeExamenTheme {
            NoteScreen("Test", "Lernzettel", "Synthetischer Lernzettel.", "", null, {}, {}, {}, "Lernzettel: Synthetisch.pdf")
        } }
        compose.onNodeWithText("Original-PDF anzeigen").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("PDF-Seite 1 von 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("PDF-Seite 1 von 2").assertIsDisplayed()
        compose.onNodeWithText("Weiter").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("PDF-Seite 2 von 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("PDF-Seite 2 von 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNodeWithText("Original-PDF anzeigen").assertExists()
    }

    @Test fun changedTextCannotShowOutdatedPdf() {
        compose.setContent { PflegeExamenTheme {
            NoteScreen("Test", "Lernzettel", "Anderer Text.", "", null, {}, {}, {}, "Lernzettel: Synthetisch.pdf")
        } }
        compose.onNodeWithText("Original-PDF anzeigen").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Das passende Original-PDF ist in dieser App-Version nicht verfügbar. Der Text bleibt weiterhin lesbar.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Das passende Original-PDF ist in dieser App-Version nicht verfügbar. Der Text bleibt weiterhin lesbar.").assertIsDisplayed()
    }
}
