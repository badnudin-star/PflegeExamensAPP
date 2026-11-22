package de.pflegeexamen.app.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import de.pflegeexamen.app.domain.content.LearningCatalog
import de.pflegeexamen.app.domain.content.PublishedCard
import de.pflegeexamen.app.domain.content.PublishedCase
import de.pflegeexamen.app.domain.content.PublishedCaseQuestion
import de.pflegeexamen.app.domain.content.PublishedQuiz
import de.pflegeexamen.app.data.progress.QuizProgressPersistence
import de.pflegeexamen.app.data.progress.QuizProgressStore
import de.pflegeexamen.app.data.progress.CardProgressStore
import de.pflegeexamen.app.data.progress.CaseProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCardProgress
import de.pflegeexamen.app.data.progress.SharedPreferencesCaseProgress
import de.pflegeexamen.app.data.progress.SharedPreferencesQuizProgress
import java.util.UUID
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

    @Test fun noteSearchJumpsToMatchAndRespectsLaterHeadingChoice() {
        val filler = (1..35).joinToString("\n\n") { "Langer Übungsabsatz Nummer $it mit etwas Text." }
        val note = "EXAMENSVORBEREITUNG · LERNZETTEL\n1\nEINLEITUNG\n$filler\n\nABSCHLUSS\nHier steht das Nadelwort als Fundstelle."
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PflegeExamenTheme { NoteScreen("Testthema", "Lernzettel", note, "Nadelwort", null, {}, {}, {}) }
        }
        compose.onNodeWithText("Hier steht das Nadelwort als Fundstelle.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Überschriften öffnen").performClick()
        compose.onNode(hasText("EINLEITUNG") and hasClickAction()).performClick()
        compose.onNodeWithText("EINLEITUNG").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("EINLEITUNG").assertIsDisplayed()
    }

    @Test fun androidPreferencesKeepSeparateLearningProgress() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "ui_test_" + UUID.randomUUID().toString() + "_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        val quizA = listOf(PublishedQuiz("q1", "2026-01-01", "Test", "Frage?", listOf("A", "B", "C", "D"), 1, "Erklärung", listOf("Test")))
        val quizB = listOf(quizA[0].copy(id = "q2", published = "2026-01-02"))
        try {
            CardProgressStore(SharedPreferencesCardProgress(isolated)).apply { setLearned("c1", true); setLearned("c2", true) }
            CaseProgressStore(SharedPreferencesCaseProgress(isolated)).setCompleted("case1", true)
            val quizzes = QuizProgressStore(SharedPreferencesQuizProgress(isolated))
            quizzes.save("2026-01-01", quizA, quizzes.load("2026-01-01", quizA).answer(1, quizA))
            quizzes.save("2026-01-02", quizB, quizzes.load("2026-01-02", quizB).answer(0, quizB))

            val cardsAgain = CardProgressStore(SharedPreferencesCardProgress(isolated))
            val casesAgain = CaseProgressStore(SharedPreferencesCaseProgress(isolated))
            val quizAgain = QuizProgressStore(SharedPreferencesQuizProgress(isolated))
            assertEquals(setOf("c1", "c2"), cardsAgain.learned.value)
            assertEquals(setOf("case1"), casesAgain.completed.value)
            assertEquals(listOf(1), quizAgain.load("2026-01-01", quizA).answers)
            assertEquals(listOf(0), quizAgain.load("2026-01-02", quizB).answers)
            cardsAgain.setLearned("c1", false)
            quizAgain.reset("2026-01-01")
            assertEquals(setOf("c2"), CardProgressStore(SharedPreferencesCardProgress(isolated)).learned.value)
            assertEquals(setOf("case1"), CaseProgressStore(SharedPreferencesCaseProgress(isolated)).completed.value)
            assertTrue(QuizProgressStore(SharedPreferencesQuizProgress(isolated)).load("2026-01-01", quizA).answers.isEmpty())
            assertEquals(listOf(0), QuizProgressStore(SharedPreferencesQuizProgress(isolated)).load("2026-01-02", quizB).answers)
        } finally {
            // The UUID namespace ensures no existing app preferences are touched, even on a real device.
            for (name in listOf("card_progress", "case_progress", "quiz_practice")) {
                base.getSharedPreferences(prefix + name, Context.MODE_PRIVATE).edit().clear().commit()
                base.deleteSharedPreferences(prefix + name)
            }
        }
    }

    @Test fun caseAnswerOnlyAppearsWhenExplicitlyExpanded() {
        val case = PublishedCase("case-one", "2026-11-13", listOf("Testthema"), "Ein fiktiver Fall.", listOf("Test"),
            listOf(PublishedCaseQuestion("Was ist die Testaufgabe?", "Dies ist die Testlösung.", listOf("Test"))), 2, "Fall A")
        compose.setContent {
            PflegeExamenTheme { CaseDetail(case, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("caseDetailList").performScrollToNode(hasText("Musterlösung anzeigen"))
        compose.onNodeWithText("Dies ist die Testlösung.").assertDoesNotExist()
        compose.onNodeWithText("Musterlösung anzeigen").performClick()
        compose.onNodeWithTag("caseDetailList").performScrollToNode(hasText("Dies ist die Testlösung."))
        compose.onNodeWithText("Dies ist die Testlösung.").assertIsDisplayed()
        compose.onNodeWithTag("caseDetailList").performScrollToNode(hasText("Musterlösung verbergen"))
        compose.onNodeWithText("Musterlösung verbergen").performClick()
        compose.onNodeWithText("Dies ist die Testlösung.").assertDoesNotExist()
    }

    @Test fun localQuizResumesAndReviewsWithoutRanking() {
        val memory = object : QuizProgressPersistence {
            val values = mutableMapOf<String, String>()
            override fun load(date: String) = values[date]
            override fun save(date: String, value: String) { values[date] = value }
            override fun remove(date: String) { values.remove(date) }
        }
        val progress = QuizProgressStore(memory)
        val questions = listOf(
            PublishedQuiz("q1", "2026-11-13", "Testthema", "Erste Testfrage?",
                listOf("Falsch eins", "Richtig eins", "Option eins C", "Option eins D"), 1, "Erklärung eins", listOf("Test")),
            PublishedQuiz("q2", "2026-11-13", "Testthema", "Zweite Testfrage?",
                listOf("Option zwei A", "Option zwei B", "Richtig zwei", "Option zwei D"), 2, "Erklärung zwei", listOf("Test"))
        )
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PflegeExamenTheme { QuizScreen(LearningCatalog(quizzes = questions), progress) { _, _ -> } }
        }
        compose.onNodeWithText("Falsch eins").performScrollTo().performClick()
        compose.onNodeWithText("Deine Antwort: Falsch eins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nicht richtig. Richtig ist: Richtig eins").assertExists()

        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Deine Antwort: Falsch eins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nächste Frage").performScrollTo().performClick()
        compose.onNodeWithText("Richtig zwei").performScrollTo().performClick()
        compose.onNodeWithText("Richtig").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nächste Frage").performScrollTo().performClick()
        compose.onNodeWithText("Übung beendet: 1 von 2 richtig.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Eine Ranglistenwertung ist noch nicht eingerichtet.").assertExists()
        compose.onNodeWithText("1 falsch beantwortete Fragen ansehen").performScrollTo().performClick()
        compose.onNodeWithText("Erste Testfrage?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Deine Antwort: Falsch eins").assertExists()
        compose.onNodeWithText("Richtige Antwort: Richtig eins").assertExists()
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
