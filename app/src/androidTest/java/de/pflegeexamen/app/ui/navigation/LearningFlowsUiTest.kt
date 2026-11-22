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
import de.pflegeexamen.app.domain.content.PublishedNote
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.cards.CardVisibility
import de.pflegeexamen.app.data.progress.QuizProgressPersistence
import de.pflegeexamen.app.data.content.PublishedContentStore
import de.pflegeexamen.app.data.quiz.ArchiveCache
import de.pflegeexamen.app.data.quiz.ArchiveDay
import de.pflegeexamen.app.data.quiz.ArchivePage
import de.pflegeexamen.app.data.quiz.ArchiveDaySummary
import de.pflegeexamen.app.data.quiz.ArchiveTransport
import de.pflegeexamen.app.data.quiz.QuizArchiveClient
import de.pflegeexamen.app.data.quiz.FileArchiveCache
import de.pflegeexamen.app.data.quiz.KeystoreTokenCipher
import de.pflegeexamen.app.data.quiz.NoBackupTokenStorage
import de.pflegeexamen.app.data.quiz.NoBackupPendingStorage
import de.pflegeexamen.app.data.quiz.RankingTokenVault
import de.pflegeexamen.app.data.quiz.TokenLoad
import de.pflegeexamen.app.data.quiz.TokenSave
import de.pflegeexamen.app.data.quiz.PendingAttemptStore
import de.pflegeexamen.app.data.quiz.PendingMarker
import de.pflegeexamen.app.data.quiz.PendingQuizReconciliation
import de.pflegeexamen.app.data.quiz.PendingQuizState
import de.pflegeexamen.app.data.quiz.AttemptLookup
import de.pflegeexamen.app.data.quiz.RankedAnswer
import de.pflegeexamen.app.data.quiz.RankedQuiz
import de.pflegeexamen.app.data.quiz.RankedQuizQuestion
import de.pflegeexamen.app.data.quiz.RankedQuizLoad
import de.pflegeexamen.app.data.quiz.RegistrationState
import de.pflegeexamen.app.data.quiz.SubmissionResult
import de.pflegeexamen.app.data.quiz.sha256
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun rankedParticipationRequiresConfirmationAndRestoresAnswers() {
        val date = "2026-11-13"
        val quiz = RankedQuiz(date, "a".repeat(64), (1..50).map {
            RankedQuizQuestion("q-$it", "Testthema", "Frage $it", listOf("Antwort A", "Antwort B", "Antwort C", "Antwort D"), 0, "Erklärung $it")
        })
        var registered = false; var registrations = 0; var submissions = 0; var submitted = emptyList<RankedAnswer>()
        val fake = object : RankedParticipationService {
            override suspend fun status(): RegistrationState = if (registered) RegistrationState.Registered("LernFuchs") else RegistrationState.NotPrepared
            override suspend fun register(name: String): RegistrationState { registrations++; registered = true; return RegistrationState.Registered(name) }
            override suspend fun load(): RankedQuizLoad = RankedQuizLoad.Ready(quiz)
            override suspend fun submit(quiz: RankedQuiz, answers: List<RankedAnswer>): SubmissionResult {
                submissions++; submitted = answers; return SubmissionResult.Recorded(date, 49, 50)
            }
        }
        val restore = StateRestorationTester(compose)
        restore.setContent { PflegeExamenTheme { RankedParticipationScreen(fake, {}) } }
        compose.onNodeWithText("Kontostatus prüfen").performClick()
        compose.onNodeWithText("Pseudonym").performTextInput("LernFuchs")
        compose.onNodeWithText("Anmelden").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle { assertEquals(0, registrations) }
        compose.onNodeWithText("Anmelden").performScrollTo().performClick()
        compose.onNodeWithText("Anmeldung senden").performClick()
        compose.onNodeWithText("Heutiges Wertungsquiz laden").performScrollTo().performClick()
        compose.onNodeWithText("Antwort B").performScrollTo().performClick()
        compose.onNodeWithText("Nicht richtig. Richtig ist: Antwort A").performScrollTo().assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Kontostatus prüfen").performScrollTo().performClick()
        compose.onNodeWithText("Heutiges Wertungsquiz laden").performScrollTo().performClick()
        compose.onNodeWithText("Nicht richtig. Richtig ist: Antwort A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nächste Frage").performScrollTo().performClick()
        repeat(49) {
            compose.onNodeWithText("Antwort A").performScrollTo().performClick()
            compose.onNodeWithText("Nächste Frage").performScrollTo().performClick()
        }
        compose.onNodeWithText("Gewertet abgeben").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle { assertEquals(0, submissions) }
        compose.onNodeWithText("Gewertet abgeben").performScrollTo().performClick()
        compose.onNodeWithText("Einmalig abgeben").performClick()
        compose.onNodeWithText("Serverergebnis: 49 von 50 richtig. Bitte in der Ranglistenansicht das Ergebnis bestätigen.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, registrations); assertEquals(1, submissions); assertEquals(50, submitted.size)
            assertEquals(RankedAnswer("q-1", 1), submitted.first()); assertEquals(RankedAnswer("q-50", 0), submitted.last())
        }
    }

    @Test fun pendingQuizClosureRequiresExplicitConfirmation() {
        val state = mutableStateOf<PendingQuizState?>(PendingQuizState.Waiting("2026-11-13"))
        var closed = 0; var checked = 0; var acknowledged = 0
        compose.setContent {
            PflegeExamenTheme {
                PendingQuizRecoveryPanel(state.value, false, null, { checked++ }, { acknowledged++ }, { closed++ })
            }
        }
        compose.onNodeWithText("Auf diesen Wertungstag verzichten").performClick()
        compose.onNodeWithText("Wertungstag sperren?").assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithText("Auf diesen Wertungstag verzichten").performClick()
        compose.onNodeWithText("Ja, Wertungstag sperren").performClick()
        compose.runOnIdle { assertEquals(1, closed); state.value = PendingQuizState.Recorded("2026-11-13", 32) }
        compose.onNodeWithText("Bestätigtes Ergebnis vom 2026-11-13: 32 von 50 richtig.").assertIsDisplayed()
        compose.onNodeWithText("Auf diesen Wertungstag verzichten").assertDoesNotExist()
        compose.onNodeWithText("Ergebnis bestätigen").performClick()
        compose.onNodeWithText("Abgabestatus prüfen").performClick()
        compose.runOnIdle { assertEquals(1, acknowledged); assertEquals(1, checked) }
    }

    @Test fun shuffledCardAndAnswerSurviveSavedStateRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PflegeExamenTheme { CardSession(cards, true, emptySet(), false, { _, _ -> }, back = {}) }
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
                }, back = {})
            }
        }
        compose.onNodeWithText(cards[0].front).assertIsDisplayed()
        compose.onNodeWithText("Gelernt · Karte ausblenden").performClick()
        compose.onNodeWithText(cards[1].front).assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(cards[0].id), learned.value) }
    }

    @Test fun cardSearchFiltersWithoutRevealingAnswersOrUnhidingCards() {
        val title = DefaultCatalogs.defaultPreparations.first().title
        val deck = listOf(
            PublishedCard("visible", title, "Sichtbare Frage", "Besonderes Suchwort", listOf("Test")),
            PublishedCard("learned", title, "Gelernte Frage", "Besonderes Suchwort", listOf("Test")),
            PublishedCard("other", title, "Andere Frage", "Andere Antwort", listOf("Test"))
        )
        val show = mutableStateOf(false)
        var selected: Pair<Int, String>? = null
        compose.setContent {
            PflegeExamenTheme { CardPicker(LearningCatalog(cards = deck), setOf("learned"), show.value, { show.value = it }, {}) { index, query -> selected = index to query } }
        }
        compose.onNodeWithText("Karteikarten durchsuchen").performTextInput("Suchwort")
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText("1 passende Karte sichtbar · 1 ausgeblendet"))
        compose.onNodeWithText("1 passende Karte sichtbar · 1 ausgeblendet").assertIsDisplayed()
        compose.onNodeWithText("Besonderes Suchwort").assertDoesNotExist()
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(0 to "Suchwort", selected) }
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText("Gelernte Karten anzeigen"))
        compose.onNodeWithText("Gelernte Karten anzeigen").performClick()
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText("2 passende Karten sichtbar · 0 ausgeblendet"))
        compose.onNodeWithText("2 passende Karten sichtbar · 0 ausgeblendet").assertExists()
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText("Karteikarten durchsuchen"))
        compose.onNodeWithContentDescription("Kartensuche löschen").performClick()
        compose.onNodeWithText("Alle sichtbaren Karten zufällig mischen").assertExists()
    }

    @Test fun cardSearchNavigationCarriesSpecialCharactersAndRestoresPicker() {
        val title = DefaultCatalogs.defaultPreparations.first().title
        val unique = UUID.randomUUID().toString()
        val query = "A&B/ä%z"
        val deck = listOf(
            PublishedCard("$unique-1", title, "Erste Navigationsfrage", "Antwort mit $query", listOf("Test")),
            PublishedCard("$unique-2", title, "Zweite Navigationsfrage", "Andere Antwort", listOf("Test"))
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PublishedContentStore(context)
        val note = PublishedNote(title, "Einfache Testnotiz", "Nicht angezeigt", "Ausführliche Testnotiz", listOf("Test"), 2)
        compose.setContent { PflegeExamenTheme { LearningAppContent(LearningCatalog(notes = listOf(note), cards = deck), store) } }
        compose.onNodeWithText("Karten", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Karteikarten durchsuchen").performTextInput(query)
        compose.onNodeWithTag("cardPickerList").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Erste Navigationsfrage").assertIsDisplayed()
        compose.onNodeWithText("1 / 1").assertExists()
        compose.onNodeWithText("Gefilterte Karten · Suche: $query").assertExists()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText(query).assertExists()

        // The existing note-to-cards route omits the optional query and must stay unfiltered.
        compose.onNodeWithText("Themen", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("topicList").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Karteikarten zu diesem Thema lernen").performScrollTo().performClick()
        compose.onNodeWithText("Erste Navigationsfrage").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithText("Gefilterte Karten · Suche: $query").assertDoesNotExist()
    }

    @Test fun filteredCardSessionOnlyShowsMatchingVisibleCards() {
        val deck = listOf(
            PublishedCard("one", "Thema", "Erste Frage", "Suchbegriff in Antwort", listOf("Test")),
            PublishedCard("two", "Thema", "Zweite Frage", "Suchbegriff in Antwort", listOf("Test")),
            PublishedCard("three", "Thema", "Dritte Frage", "Andere Antwort", listOf("Test"))
        )
        val learned = mutableStateOf(setOf("two"))
        compose.setContent {
            PflegeExamenTheme {
                CardSession(CardVisibility.matching(deck, "Suchbegriff"), false, learned.value, false,
                    { id, value -> learned.value = if (value) learned.value + id else learned.value - id }, "Suchbegriff", back = {})
            }
        }
        compose.onNodeWithText("Gefilterte Karten · Suche: Suchbegriff").assertExists()
        compose.onNodeWithText("Erste Frage").assertIsDisplayed()
        compose.onNodeWithText("1 / 1").assertExists()
        compose.onNodeWithText("Suchbegriff in Antwort").assertDoesNotExist()
        compose.onNodeWithText("Gelernt · Karte ausblenden").performClick()
        compose.onNodeWithText("Keine sichtbaren Karten für diese Suche mehr. Ändere die Suche oder blende gelernte Karten in der Übersicht wieder ein.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("one", "two"), learned.value) }
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

    @Test fun noteSearchNavigatesBetweenMatchesAndKeepsSelectionAfterRestore() {
        val filler = (1..35).joinToString("\n\n") { "Füllabsatz $it." }
        val note = "Erste Nadelwort-Fundstelle.\n\n$filler\n\nZweite Nadelwort-Fundstelle.\n\n$filler\n\nDritte Nadelwort-Fundstelle."
        val restore = StateRestorationTester(compose)
        restore.setContent { PflegeExamenTheme { NoteScreen("Testthema", "Lernzettel", note, "Nadelwort", null, {}, {}, {}) } }
        compose.onNodeWithText("Erste Nadelwort-Fundstelle.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vorheriger Suchtreffer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Nächster Suchtreffer").performClick()
        compose.onNodeWithText("Zweite Nadelwort-Fundstelle.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächster Suchtreffer").performClick()
        compose.onNodeWithText("Dritte Nadelwort-Fundstelle.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächster Suchtreffer").assertIsNotEnabled()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Dritte Nadelwort-Fundstelle.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächster Suchtreffer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Vorheriger Suchtreffer").performClick()
        compose.onNodeWithText("Zweite Nadelwort-Fundstelle.").assertIsDisplayed()
    }

    @Test fun noteSearchNavigatesToDifferentOccurrencesInsideLongParagraph() {
        val paragraph = "Beginn Nadelwort " + (1..160).joinToString(" ") { "langer Übungstext $it" } + " Ende Nadelwort."
        val note = paragraph + "\n\n" + (1..20).joinToString("\n\n") { "Nachfolgender Absatz $it." }
        compose.setContent { PflegeExamenTheme { NoteScreen("Testthema", "Lernzettel", note, "Nadelwort", null, {}, {}, {}) } }
        val initialTop = compose.onNodeWithText(paragraph).getUnclippedBoundsInRoot().top.value
        compose.onNodeWithContentDescription("Nächster Suchtreffer").performClick()
        val laterTop = compose.onNodeWithText(paragraph).getUnclippedBoundsInRoot().top.value
        assertTrue("Der zweite Treffer soll weiter unten im selben Absatz liegen", laterTop < initialTop - 200f)
        compose.onNodeWithContentDescription("Nächster Suchtreffer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Vorheriger Suchtreffer").performClick()
        val restoredTop = compose.onNodeWithText(paragraph).getUnclippedBoundsInRoot().top.value
        assertTrue("Zurück zum ersten Treffer im Absatz", restoredTop > laterTop + 200f)
    }

    @Test fun noteSearchWithoutTextMatchDoesNotOfferNavigation() {
        compose.setContent { PflegeExamenTheme { NoteScreen("Titel gefunden", "Vereinfacht", "Anderer Text.", "Titel gefunden", null, {}, {}, {}) } }
        compose.onNodeWithText("Kein Treffer in dieser Lernzettelversion").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nächster Suchtreffer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Vorheriger Suchtreffer").assertDoesNotExist()
    }

    @Test fun androidKeystoreProtectsTokenAndDetectsLostKey() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val folder = File(base.cacheDir, "ranking-key-test-$unique")
        check(folder.mkdirs())
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = folder }
        val alias = "ranking-test-$unique"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!keystore.containsAlias(alias))
        val token = "ab".repeat(32) // synthetic credential, never used with a real service
        try {
            val vault = RankingTokenVault(NoBackupTokenStorage(isolated), KeystoreTokenCipher(base.packageName, alias))
            assertEquals(TokenSave.SAVED, vault.saveNew(token))
            val file = File(folder, "quiz-ranking-token.bin")
            assertTrue(file.exists())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(token))
            val reopened = RankingTokenVault(NoBackupTokenStorage(isolated), KeystoreTokenCipher(base.packageName, alias))
            assertEquals(token, (reopened.load() as TokenLoad.Available).token)
            keystore.deleteEntry(alias)
            assertEquals(TokenLoad.Unavailable, reopened.load())
            assertEquals(TokenSave.ALREADY_EXISTS, reopened.saveNew("cd".repeat(32)))
            assertFalse(keystore.containsAlias(alias))
            assertTrue(file.exists())
        } finally { keystore.deleteEntry(alias); folder.deleteRecursively() }
    }

    @Test fun androidPendingMarkerSurvivesReopenAndNeedsMatchingServerHash() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val folder = File(base.cacheDir, "ranking-pending-test-$unique")
        check(folder.mkdirs())
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = folder }
        val alias = "ranking-test-$unique"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!keystore.containsAlias(alias))
        val token = "ab".repeat(32)
        val marker = PendingMarker(date = "2020-01-01", quizHash = "1".repeat(64),
            accountHash = sha256(token.toByteArray(Charsets.US_ASCII)), payloadHash = "2".repeat(64))
        try {
            val vault = RankingTokenVault(NoBackupTokenStorage(isolated), KeystoreTokenCipher(base.packageName, alias))
            assertEquals(TokenSave.SAVED, vault.saveNew(token))
            assertTrue(PendingAttemptStore(NoBackupPendingStorage(isolated)).begin(marker))
            val file = File(folder, "quiz-ranking-pending.json")
            assertFalse(file.readText().contains(token))
            val reopened = PendingAttemptStore(NoBackupPendingStorage(isolated))
            assertFalse(reopened.begin(marker))
            val waiting = PendingQuizReconciliation(reopened, vault) { _, _ -> AttemptLookup.NotFound }
            assertEquals(PendingQuizState.Waiting(marker.date), waiting.reconcile())
            assertFalse(waiting.acknowledgeRecorded())
            val mismatch = PendingQuizReconciliation(reopened, vault) { _, _ -> AttemptLookup.Found(marker.date, 42, 50, "3".repeat(64)) }
            assertEquals(PendingQuizState.DifferentOrUnverifiedAttempt(marker.date), mismatch.reconcile())
            assertTrue(file.exists())
            val confirmed = PendingQuizReconciliation(reopened, vault) { _, _ -> AttemptLookup.Found(marker.date, 42, 50, marker.payloadHash) }
            assertEquals(PendingQuizState.Recorded(marker.date, 42), confirmed.reconcile())
            assertTrue(confirmed.acknowledgeRecorded())
            assertFalse(file.exists())
            assertEquals(token, (vault.load() as TokenLoad.Available).token)
        } finally { keystore.deleteEntry(alias); folder.deleteRecursively() }
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

    @Test fun quizArchiveLoadsOnDemandAndReopensCachedDayOffline() {
        val day = "2020-01-01"
        val question = PublishedQuiz("archive-q", day, DefaultCatalogs.defaultPreparations.first().title,
            "Archivfrage?", listOf("Antwort A", "Antwort B", "Antwort C", "Antwort D"), 1, "Archiv-Erklärung", listOf("Test"))
        val entries = ConcurrentHashMap<String, ByteArray>()
        val cache = object : ArchiveCache {
            override fun dates() = entries.keys.toList()
            override fun read(date: String) = entries[date]
            override fun writeNew(date: String, bytes: ByteArray) { check(entries.putIfAbsent(date, bytes) == null) }
        }
        val online = AtomicBoolean(true)
        val client = QuizArchiveClient("https://example.test/content", ArchiveTransport { url, _ ->
            if (!online.get()) null else if (url.contains("?limit=")) Json.encodeToString(ArchivePage(listOf(ArchiveDaySummary(day, 1)))).toByteArray()
            else Json.encodeToString(ArchiveDay(day, listOf(question))).toByteArray()
        }, cache)
        val memory = object : QuizProgressPersistence {
            val values = mutableMapOf<String, String>()
            override fun load(date: String) = values[date]
            override fun save(date: String, value: String) { values[date] = value }
            override fun remove(date: String) { values.remove(date) }
        }
        val selected = mutableStateOf<String?>(null)
        val progress = QuizProgressStore(memory)
        compose.setContent {
            PflegeExamenTheme {
                val chosen = selected.value
                if (chosen == null) QuizArchivePicker(client, emptySet(), open = { selected.value = it }, back = {})
                else ArchivedQuizScreen(chosen, client, LearningCatalog(), progress, openNote = { _, _ -> }, back = { selected.value = null })
            }
        }
        compose.onNodeWithText("Archiv online laden").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(day).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(day).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Archivfrage?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Antwort B").performScrollTo().performClick()
        compose.onNodeWithText("Deine Antwort: Antwort B").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { online.set(false) }
        compose.onNodeWithContentDescription("Zurück zum Archiv").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(day).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Auf diesem Gerät gespeichert").assertExists()
        compose.onNodeWithText(day).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Deine Antwort: Antwort B").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Deine Antwort: Antwort B").performScrollTo().assertIsDisplayed()
    }

    @Test fun androidArchiveCacheNeverOverwritesExistingOrInterruptedFiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "archive-ui-test-" + UUID.randomUUID())
        check(folder.mkdirs())
        val isolated = object : ContextWrapper(base) { override fun getFilesDir(): File = folder }
        try {
            val cache = FileArchiveCache(isolated)
            val day = "2020-01-01"
            val bytes = "Testdaten".toByteArray()
            assertNull(cache.read(day))
            cache.writeNew(day, bytes)
            assertArrayEquals(bytes, cache.read(day))
            assertEquals(listOf(day), cache.dates())
            try { cache.writeNew(day, "anders".toByteArray()); fail("Darf nicht überschreiben") } catch (_: IllegalStateException) { }
            assertArrayEquals(bytes, cache.read(day))
            File(folder, "quiz-archive/$day.json.new").writeText("unvollständig")
            try { cache.read(day); fail("Unvollständige Datei darf nicht überschrieben werden") } catch (_: IllegalStateException) { }
            assertArrayEquals(bytes, File(folder, "quiz-archive/$day.json").readBytes())
        } finally { folder.deleteRecursively() }
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

    @Test fun topicSearchFindsBothVersionsAndUpdatesAfterCatalogChange() {
        val title = DefaultCatalogs.defaultPreparations.first().title
        val note = PublishedNote(title, "Vereinfachtes Nadelwort", "Unbenutzt", "Ausführliches Nadelwort", listOf("Test"), 2)
        val catalog = mutableStateOf(LearningCatalog(notes = listOf(note)))
        var opened: Triple<Int, String, String>? = null
        compose.setContent {
            PflegeExamenTheme { Topics(catalog.value) { index, depth, query -> opened = Triple(index, depth, query) } }
        }
        compose.onNodeWithText("Themen und Lernzettel durchsuchen").performTextInput("Nadelwort")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Suchtreffer im Lernzettel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Suchtreffer im vereinfachten Lernzettel").assertExists()
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(Triple(0, "main", "Nadelwort"), opened) }

        compose.runOnIdle { catalog.value = LearningCatalog(notes = listOf(note.copy(detailed = "Neuer Inhalt", short = "Neuer Inhalt"))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keine passenden Themen oder Lernzettel gefunden.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Suchtreffer im Lernzettel").assertDoesNotExist()
        compose.onNodeWithText("Themen und Lernzettel durchsuchen").performTextClearance()
        compose.onNodeWithText("Themen und Lernzettel durchsuchen").performTextInput("Neuer Inhalt")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Suchtreffer im Lernzettel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Suchtreffer im vereinfachten Lernzettel").assertExists()
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
