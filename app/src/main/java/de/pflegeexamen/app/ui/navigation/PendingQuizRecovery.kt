package de.pflegeexamen.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.pflegeexamen.app.data.quiz.AndroidPendingQuizAttempt
import de.pflegeexamen.app.data.quiz.AndroidRankingTokenVault
import de.pflegeexamen.app.data.quiz.HttpsAttemptLookupTransport
import de.pflegeexamen.app.data.quiz.HttpsClosureTransport
import de.pflegeexamen.app.data.quiz.PendingClosureState
import de.pflegeexamen.app.data.quiz.PendingQuizClosure
import de.pflegeexamen.app.data.quiz.PendingQuizReconciliation
import de.pflegeexamen.app.data.quiz.PendingQuizState
import de.pflegeexamen.app.data.quiz.PendingResultLookup
import de.pflegeexamen.app.data.quiz.QuizAttemptClose
import de.pflegeexamen.app.data.quiz.QuizAttemptLookup
import kotlinx.coroutines.launch

/** Opt-in, no request until the user asks. Disabled with an empty build-time URL. */
@Composable
internal fun PendingQuizRecovery(url: String) {
    if (url.isBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context.applicationContext) { AndroidRankingTokenVault.create(context) }
    val store = remember(context.applicationContext) { AndroidPendingQuizAttempt.create(context) }
    val reconciliation = remember(url, vault, store) {
        val lookup = QuizAttemptLookup(url, HttpsAttemptLookupTransport())
        PendingQuizReconciliation(store, vault, PendingResultLookup { date, token -> lookup.lookup(date, token) })
    }
    val closure = remember(url, vault, store) {
        PendingQuizClosure(store, vault, QuizAttemptClose(url, HttpsClosureTransport()))
    }
    var state by remember { mutableStateOf<PendingQuizState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    PendingQuizRecoveryPanel(state, busy, message,
        refresh = {
            busy = true; message = null
            scope.launch { try { state = reconciliation.reconcile() } finally { busy = false } }
        },
        acknowledge = {
            busy = true; message = null
            scope.launch {
                try {
                    if (reconciliation.acknowledgeRecorded()) { state = PendingQuizState.None; message = "Ergebnis bestätigt." }
                    else { state = PendingQuizState.StorageUnavailable }
                } finally { busy = false }
            }
        },
        close = {
            busy = true; message = null
            scope.launch {
                try {
                    when (val value = closure.close()) {
                        PendingClosureState.None -> state = PendingQuizState.None
                        PendingClosureState.StorageUnavailable -> state = PendingQuizState.StorageUnavailable
                        PendingClosureState.CredentialUnavailable -> state = PendingQuizState.CredentialUnavailable
                        PendingClosureState.CredentialMismatch -> state = PendingQuizState.CredentialMismatch
                        is PendingClosureState.Uncertain -> { state = PendingQuizState.Waiting(value.date); message = "Die Sperre ist nicht bestätigt. Bitte später erneut prüfen." }
                        is PendingClosureState.Closed -> { state = PendingQuizState.None; message = "Der Wertungstag ${value.date} wurde gesperrt. Lokales Üben bleibt möglich." }
                        is PendingClosureState.DifferentOrUnverifiedAttempt -> state = PendingQuizState.DifferentOrUnverifiedAttempt(value.date)
                        is PendingClosureState.Recorded -> state = PendingQuizState.Recorded(value.date, value.correct)
                    }
                } finally { busy = false }
            }
        }
    )
}

/** Pure rendering/callback boundary, allowing tests without a server or real credentials. */
@Composable
internal fun PendingQuizRecoveryPanel(
    state: PendingQuizState?, busy: Boolean, message: String?,
    refresh: () -> Unit, acknowledge: () -> Unit, close: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ungeklärte Quizabgabe", style = MaterialTheme.typography.titleMedium)
        Text("Nach einer unterbrochenen Abgabe kannst du hier den Status prüfen. Ein fehlendes Ergebnis beweist nicht, dass die Abgabe nicht noch ankommt.")
        when (state) {
            null -> Text("Der Status wurde noch nicht geprüft.")
            PendingQuizState.None -> Text("Keine ausstehende Abgabe auf diesem Gerät.")
            PendingQuizState.StorageUnavailable -> Text("Der lokale Speicher ist nicht verfügbar oder der Status hat sich geändert. Bitte erneut prüfen.")
            PendingQuizState.CredentialUnavailable -> Text("Der Zugang ist auf diesem Gerät nicht verfügbar. Nichts wurde gelöscht.")
            PendingQuizState.CredentialMismatch -> Text("Der gespeicherte Zugang passt nicht zur ausstehenden Abgabe. Nichts wurde gelöscht.")
            is PendingQuizState.Waiting -> Text("Abgabe vom ${state.date}: Ergebnis oder Sperre noch nicht bestätigt.")
            is PendingQuizState.DifferentOrUnverifiedAttempt -> Text("Für ${state.date} liegt ein Ergebnis vor, das dieser Abgabe nicht sicher zugeordnet werden kann. Der Status bleibt offen.")
            is PendingQuizState.Recorded -> Text("Bestätigtes Ergebnis vom ${state.date}: ${state.correct} von ${state.total} richtig.")
        }
        message?.let { Text(it) }
        if (busy) Text("Bitte warten …")
        OutlinedButton(onClick = refresh, enabled = !busy) { Text("Abgabestatus prüfen") }
        if (state is PendingQuizState.Recorded) {
            Button(onClick = acknowledge, enabled = !busy) { Text("Ergebnis bestätigen") }
        }
        if (state is PendingQuizState.Waiting) {
            OutlinedButton(onClick = { confirming = true }, enabled = !busy) { Text("Auf diesen Wertungstag verzichten") }
        }
    }
    if (confirming && state is PendingQuizState.Waiting && !busy) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Wertungstag sperren?") },
            text = { Text("Falls für ${state.date} noch kein Ergebnis gespeichert wurde, wird die Wertung dieses Tages für dein Konto unwiderruflich gesperrt. Auch eine verspätete Abgabe zählt dann nicht. Lokales Üben bleibt möglich. Ist bereits ein Ergebnis gespeichert, wird es stattdessen geprüft.") },
            confirmButton = { TextButton(onClick = { confirming = false; close() }) { Text("Ja, Wertungstag sperren") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Abbrechen") } }
        )
    }
}
