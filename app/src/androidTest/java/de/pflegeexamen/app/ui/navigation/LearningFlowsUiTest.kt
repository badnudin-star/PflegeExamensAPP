package de.pflegeexamen.app.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import de.pflegeexamen.app.domain.content.LearningCatalog
import de.pflegeexamen.app.domain.content.PublishedCard
import de.pflegeexamen.app.domain.content.PublishedCase
import de.pflegeexamen.app.domain.content.PublishedCaseQuestion
import de.pflegeexamen.app.ui.theme.PflegeExamenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses synthetic data only; the real catalog and all network services are untouched. */
class LearningFlowsUiTest {
    @get:Rule val compose = createComposeRule()
    private val cards = (1..8).map { PublishedCard("c-$it", "Übungsthema", "Kartenfrage $it", "Kartenantwort $it", listOf("Test")) }

    private fun visibleQuestion(): PublishedCard = cards.single { card ->
        compose.onAllNodesWithText(card.front).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun shuffledCardAndAnswerSurviveSavedStateRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PflegeExamenTheme { CardSession(cards, true, emptySet(), false, { _, _ -> }, {}) }
        }
        compose.onNodeWithContentDescription("Nächste Karte").performClick()
        val selected = visibleQuestion()
        compose.onNodeWithText(selected.front).performClick()
        compose.onNodeWithText(selected.back).assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächste Karte").performClick()
        val following = visibleQuestion()
        compose.onNodeWithContentDescription("Vorherige Karte").performClick()
        compose.onNodeWithText(selected.front).performClick()
        compose.onNodeWithText(selected.back).assertIsDisplayed()

        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(selected.back).assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächste Karte").performClick()
        compose.onNodeWithText(following.front).assertIsDisplayed()
    }

    @Test fun individualCardCanBeHiddenWithoutHidingOtherCards() {
        val learned = mutableStateOf(emptySet<String>())
        compose.setContent {
            PflegeExamenTheme {
                CardSession(cards.take(2), false, learned.value, false, { id, value ->
                    learned.value = if (value) learned.value + id else learned.value - id
                }, {})
            }
        }
        compose.onNodeWithText(cards[0].front).assertIsDisplayed()
        compose.onNodeWithText("Gelernt · Karte ausblenden").performClick()
        compose.onNodeWithText(cards[1].front).assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(cards[0].id), learned.value) }
    }

    @Test fun noteOutlineJumpsToSelectedHeading() {
        val note = """
            EXAMENSVORBEREITUNG · LERNZETTEL
            1
            ERSTER ABSCHNITT
            Ein Text für den ersten Abschnitt.
            ZWEITER ABSCHNITT
            Ein Text für den zweiten Abschnitt.
        """.trimIndent()
        compose.setContent {
            PflegeExamenTheme { NoteScreen("Testthema", "Lernzettel", note, "", null, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("Überschriften öffnen").performClick()
        compose.onNode(hasText("ZWEITER ABSCHNITT") and hasClickAction()).performClick()
        compose.onNodeWithText("ZWEITER ABSCHNITT").assertIsDisplayed()
    }

    @Test fun caseSearchShowsMatchingDayWithoutOpeningAnswers() {
        val case = PublishedCase("case-one", "2026-11-13", listOf("Testdiagnose"), "Falltext", listOf("Test"),
            listOf(PublishedCaseQuestion("Testaufgabe", "Suchwort in der Testantwort", listOf("Test"))), 2, "Testfall")
        var opened = ""
        compose.setContent {
            PflegeExamenTheme { CasePicker(LearningCatalog(cases = listOf(case)), emptySet(), { _, _ -> }, { opened = it }) }
        }
        compose.onNodeWithText("Fallbeispiele durchsuchen").performTextInput("Suchwort")
        compose.onNodeWithTag("caseList").performScrollToNode(hasText("Testfall"))
        compose.onNodeWithText("Testfall").assertIsDisplayed()
        compose.onNodeWithText("Treffer: Antworten").assertExists()
        compose.onNodeWithText("Suchwort in der Testantwort").assertDoesNotExist()
        compose.onNodeWithText("Testfall").performClick()
        compose.runOnIdle { assertEquals("case-one", opened) }
    }
}
