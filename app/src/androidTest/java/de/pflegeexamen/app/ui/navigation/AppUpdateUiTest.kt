package de.pflegeexamen.app.ui.navigation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.pflegeexamen.app.data.update.AppUpdateState
import de.pflegeexamen.app.ui.theme.PflegeExamenTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyUpdateRequiresExplicitInstallAndWarnsNotToUninstall() {
        var installed = false
        compose.setContent { PflegeExamenTheme {
            UpdatePromptContent(AppUpdateState.Ready("3.0.9", 20), "", false, {}, { installed = true })
        } }
        compose.onNodeWithText("App-Update 3.0.9").assertIsDisplayed()
        compose.onNodeWithText("Das signierte Update wurde heruntergeladen und geprüft. Android fragt vor der Installation nach deiner Bestätigung. App nicht deinstallieren. ").assertIsDisplayed()
        compose.onNodeWithText("Installieren").performClick()
        assertTrue(installed)
    }

    @Test fun meteredConnectionDoesNotOfferInstallation() {
        compose.setContent { PflegeExamenTheme {
            UpdatePromptContent(AppUpdateState.WaitingForWifi("3.0.9", 20), "", false, {}, {})
        } }
        compose.onNodeWithText("Installieren").assertDoesNotExist()
        compose.onNodeWithText("Später").assertIsDisplayed()
    }
}
