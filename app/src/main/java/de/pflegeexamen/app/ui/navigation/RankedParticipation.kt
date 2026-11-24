package de.pflegeexamen.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.pflegeexamen.app.data.quiz.*
import kotlinx.coroutines.launch

/** Narrow boundary so the flow can be exercised with synthetic services without network or real tokens. */
internal interface RankedParticipationService {
    suspend fun status(): RegistrationState
    suspend fun register(name: String): RegistrationState
    suspend fun load(): RankedQuizStart
    suspend fun submit(quiz: RankedQuiz, answers: List<RankedAnswer>): SubmissionResult
    suspend fun deleteAccount(): AccountDeletionResult
    suspend fun exportRecovery(): RecoveryExport
    suspend fun restoreRecovery(code: String): RecoveryRestore
}

@Composable
internal fun RankedParticipation(url: String, back: () -> Unit) {
    val context = LocalContext.current
    val service = remember(url, context.applicationContext) {
        val vault = AndroidRankingTokenVault.create(context)
        val pending = AndroidPendingQuizAttempt.create(context)
        val gate = AndroidRankingOperationGate.get(context)
        val registration = QuizRegistration(url, vault, SecureRankingTokenGenerator(), HttpsRegistrationTransport())
        val quiz = RankedQuizClient(url, HttpsRankedQuizTransport())
        val submit = QuizAttemptSubmit(url, vault, pending, HttpsQuizSubmissionTransport())
        val lookup = QuizAttemptLookup(url, HttpsAttemptLookupTransport())
        val reconciliation = PendingQuizReconciliation(pending, vault, PendingResultLookup { date, token -> lookup.lookup(date, token) })
        val start = RankedQuizStartCheck(StartPendingCheck { reconciliation.reconcile() }, StartQuizLoader { quiz.load() },
            vault, StartResultLookup { date, token -> lookup.lookup(date, token) })
        val deletion = RankingAccountDeletion(url, vault, pending, gate, HttpsAccountDeletionTransport())
        val recovery = RankingRecovery(url, vault, pending, gate, HttpsRegistrationTransport())
        object : RankedParticipationService {
            override suspend fun status(): RegistrationState = gate.unlessDeleting(RegistrationState.DeletionPending) { registration.status() }
            override suspend fun register(name: String): RegistrationState = gate.unlessDeleting(RegistrationState.DeletionPending) { registration.register(name) }
            override suspend fun load(): RankedQuizStart = gate.unlessDeleting(RankedQuizStart.Pending) { start.start() }
            override suspend fun submit(quiz: RankedQuiz, answers: List<RankedAnswer>): SubmissionResult =
                gate.unlessDeleting(SubmissionResult.DeletionPending) { submit.submit(quiz.date, quiz.fingerprint, answers) }
            override suspend fun deleteAccount() = deletion.delete()
            override suspend fun exportRecovery() = recovery.export()
            override suspend fun restoreRecovery(code: String) = recovery.restore(code)
        }
    }
    RankedParticipationScreen(service, back)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RankedParticipationScreen(service: RankedParticipationService, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var busy by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<RegistrationState?>(null) }
    var username by rememberSaveable { mutableStateOf("") }
    var quiz by remember { mutableStateOf<RankedQuiz?>(null) }
    // Save only the small response sequence, never the token or the full question content.
    var savedDate by rememberSaveable { mutableStateOf("") }
    var savedFingerprint by rememberSaveable { mutableStateOf("") }
    var selections by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var submission by remember { mutableStateOf<SubmissionResult?>(null) }
    var confirmRegister by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        busy = true
        try { account = service.status() } finally { busy = false }
    }

    fun loadQuiz() {
        busy = true; message = null
        scope.launch {
            try {
                when (val loaded = service.load()) {
                    is RankedQuizStart.Ready -> {
                        val next = loaded.quiz
                        val resume = savedDate == next.date && savedFingerprint == next.fingerprint &&
                            index in 0..next.questions.size && selections.length <= next.questions.size &&
                            (selections.length == index || (index < next.questions.size && selections.length == index + 1)) &&
                            selections.all { it in '0'..'3' }
                        if (!resume) {
                            if (selections.isNotEmpty()) message = "Quiztag oder Quizversion hat sich geändert. Die vorherigen Antworten können nicht übertragen werden."
                            savedDate = next.date; savedFingerprint = next.fingerprint; selections = ""; index = 0
                        }
                        quiz = next; submission = null
                    }
                    is RankedQuizStart.Closed -> message = "Der Wertungstag ${loaded.date} ist für dieses Konto gesperrt. Lokales Üben bleibt möglich."
                    RankedQuizStart.Pending -> message = "Es gibt eine ungeklärte oder unbestätigte Abgabe. Bitte zuerst den Abgabestatus in der Ranglistenansicht prüfen."
                    is RankedQuizStart.AlreadyRecorded -> message = "Für ${loaded.date} ist bereits ein Ergebnis gespeichert: ${loaded.correct} von ${loaded.total} richtig. Ein weiterer Wertungsversuch ist nicht möglich."
                    RankedQuizStart.Unavailable -> message = "Das Wertungsquiz oder der Ergebnisstatus ist nicht verfügbar. Lokales Üben ist weiterhin möglich."
                }
            } finally { busy = false }
        }
    }
    if (confirmRegister) AlertDialog(
        onDismissRequest = { confirmRegister = false }, title = { Text("Für die Rangliste anmelden?") },
        text = { Text("Dein Pseudonym und deine späteren Quizpunkte können öffentlich erscheinen. Der Dienst verarbeitet Verbindungsdaten; das Gerät speichert einen Zugangsschlüssel. Bei Geräteverlust oder Neuinstallation kann der Zugang derzeit verloren gehen. Bei Kontolöschung bleiben ein kryptografischer Token-Hash als Sperre und möglicherweise Daten in Sicherungen oder externen Kopien erhalten. Die Rangliste ist leicht manipulierbar und keine faire Prüfung. Nur mit einem Pseudonym ohne personenbezogene Angaben teilnehmen.") },
        confirmButton = { TextButton(onClick = {
            confirmRegister = false; busy = true; message = null
            scope.launch { try { account = service.register(username) } finally { busy = false } }
        }) { Text("Anmeldung senden") } },
        dismissButton = { TextButton(onClick = { confirmRegister = false }) { Text("Abbrechen") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Ranglistenkonto löschen?") },
        text = { Text("Dein Pseudonym und deine Wertungen werden aus der aktiven Rangliste entfernt; die Löschung ist nicht rückgängig zu machen. Ein Hash des Zugangsschlüssels bleibt als Sperre gespeichert, damit eine verspätete Anmeldung das Konto nicht wiederherstellt. In Sicherungen und bereits verbreiteten Kopien können Daten verbleiben. Bei unklarer Antwort muss die Löschung erneut geprüft werden. Lokale Übungen bleiben erhalten.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false; busy = true; message = null
            scope.launch {
                try {
                    when (service.deleteAccount()) {
                        AccountDeletionResult.Deleted -> {
                            account = RegistrationState.NotPrepared; quiz = null; username = ""; savedDate = ""; savedFingerprint = ""; selections = ""; index = 0; submission = null
                            message = "Die Löschung wurde vom Server bestätigt und der lokale Zugang entfernt."
                        }
                        AccountDeletionResult.Uncertain -> { account = RegistrationState.DeletionPending; message = "Die Löschung ist noch nicht bestätigt. Bitte erneut versuchen; der Zugang bleibt gesperrt." }
                        AccountDeletionResult.LocalCleanupIncomplete -> { account = RegistrationState.DeletionPending; message = "Die Löschung oder lokale Bereinigung ist nicht abgeschlossen. Bitte erneut versuchen." }
                        AccountDeletionResult.CredentialUnavailable -> { account = RegistrationState.DeletionPending; message = "Der Zugang ist nicht verfügbar. Die Löschung kann hier nicht bestätigt werden." }
                        AccountDeletionResult.NoAccount -> { account = RegistrationState.NotPrepared; message = "Auf diesem Gerät ist kein Zugang vorhanden." }
                        AccountDeletionResult.NotConfigured -> message = "Der Dienst ist nicht eingerichtet."
                    }
                } finally { busy = false }
            }
        }) { Text("Ja, Konto löschen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
    )
    if (confirmSubmit) AlertDialog(
        onDismissRequest = { confirmSubmit = false }, title = { Text("Quiz gewertet abgeben?") },
        text = { Text("Deine 50 Antworten werden zur serverseitigen Bewertung übertragen. Pro Konto und Tag ist nur ein gewerteter Versuch möglich. Nach einer Unterbrechung darf nicht blind erneut abgegeben werden; prüfe den Status in der Ranglistenansicht.") },
        confirmButton = { TextButton(onClick = {
            confirmSubmit = false
            val current = quiz
            if (current != null && index == current.questions.size && selections.length == current.questions.size && selections.all { it in '0'..'3' }) {
                busy = true; message = null
                val answers = current.questions.mapIndexed { i, q -> RankedAnswer(q.id, selections[i] - '0') }
                scope.launch { try { submission = service.submit(current, answers) } finally { busy = false } }
            }
        }) { Text("Einmalig abgeben") } },
        dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Abbrechen") } }
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Wertungsquiz") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wertungsquiz", style = MaterialTheme.typography.headlineMedium)
            Text("Wähle einen Namen, beantworte das Tagesquiz und sieh, wie viele Antworten richtig waren. Die Rangliste ist ein Spiel und keine Prüfung.")
            Text("Die Wertung ist getrennt vom lokalen Üben. Lösungen sind sichtbar und mehrere Konten möglich.", style = MaterialTheme.typography.bodySmall)
            when (val currentAccount = account) {
                null -> Text("Ranglistenzugang wird geprüft …")
                RegistrationState.NotPrepared -> Text("Auf diesem Gerät ist noch kein Ranglistenzugang vorbereitet.")
                RegistrationState.NotRegistered -> Text("Zum Abfragezeitpunkt wurde kein Konto gefunden. Eine frühere Anmeldung könnte noch eintreffen; nur mit demselben Pseudonym erneut versuchen.")
                RegistrationState.InvalidName -> Text("Pseudonym ungültig: 2 bis 24 Zeichen, Buchstaben, Zahlen, Leerzeichen oder _ . -.")
                RegistrationState.NameConflict -> Text("Das Token ist bereits mit einem anderen Pseudonym registriert. Bitte Kontostatus prüfen.")
                RegistrationState.StorageUnavailable -> Text("Der Zugangsspeicher ist nicht verfügbar. Es wurde kein neuer Zugang überschrieben.")
                RegistrationState.DeletionPending -> Text("Eine Kontolöschung ist noch offen. Anmeldung und Abgabe sind gesperrt, bis sie abgeschlossen ist.")
                RegistrationState.NotConfigured -> Text("Die Rangliste ist nicht eingerichtet.")
                RegistrationState.Uncertain -> Text("Der Anmeldestatus ist unklar. Bitte prüfen; beim erneuten Versuch dasselbe Pseudonym verwenden.")
                is RegistrationState.Registered -> Text("Angemeldet als ${currentAccount.username}.")
            }
            if (busy) Text("Bitte warten …")
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch { try { account = service.status() } finally { busy = false } }
            }) { Text("Kontostatus prüfen") }
            if (account != null && account !is RegistrationState.Registered && account != RegistrationState.StorageUnavailable && account != RegistrationState.NotConfigured && account != RegistrationState.DeletionPending) {
                OutlinedTextField(value = username, onValueChange = { if (it.length <= 24) username = it }, enabled = !busy,
                    label = { Text("Pseudonym") }, singleLine = true)
                Button(enabled = !busy && QuizRegistration.validName(username), onClick = { confirmRegister = true }) { Text("Anmelden") }
            }
            TextButton(enabled = !busy, onClick = { showRecovery = !showRecovery }) {
                Text(if (showRecovery) "Zugangsoptionen ausblenden" else "Zugangsoptionen")
            }
            if (showRecovery) RankingRecoveryControls(service,
                canExport = account is RegistrationState.Registered,
                canRestore = account == RegistrationState.NotPrepared || account == RegistrationState.NotRegistered || account == RegistrationState.Uncertain,
                busy = busy, setBusy = { busy = it }, onRestored = { account = RegistrationState.Registered(it) })
            if (account is RegistrationState.Registered || account == RegistrationState.DeletionPending) {
                OutlinedButton(enabled = !busy, onClick = { confirmDelete = true }) {
                    Text(if (account == RegistrationState.DeletionPending) "Kontolöschung fortsetzen" else "Ranglistenkonto löschen")
                }
            }
            if (account is RegistrationState.Registered && quiz == null) {
                Text("Antworten werden für die Wiederherstellung des Bildschirms vorübergehend im Android-Zustand gespeichert. Beim Verlassen des Bereichs oder nach Prozessverlust kann der Fortschritt verloren gehen.")
                Button(enabled = !busy, onClick = { loadQuiz() }) { Text("Heutiges Wertungsquiz laden") }
            }
            message?.let { Text(it) }
            val current = quiz
            if (current != null && account is RegistrationState.Registered) {
                Text("Quiztag ${current.date} · ${current.questions.size} Fragen")
                if (index < current.questions.size) {
                    val q = current.questions[index]
                    val choice = selections.getOrNull(index)?.digitToInt()
                    Text("${index + 1} / ${current.questions.size} · ${q.topic}", style = MaterialTheme.typography.labelLarge)
                    Text(q.question, style = MaterialTheme.typography.titleLarge)
                    q.options.forEachIndexed { option, text ->
                        OutlinedButton(onClick = { if (selections.length == index) selections += option.toString() },
                            enabled = !busy && choice == null, modifier = Modifier.fillMaxWidth()) { Text(text) }
                    }
                    if (choice != null) {
                        Text(if (choice == q.correctIndex) "Richtig" else "Nicht richtig. Richtig ist: ${q.options[q.correctIndex]}")
                        Text(q.explanation)
                        Button(enabled = !busy, onClick = { index++; scope.launch { scroll.scrollTo(0) } }) { Text("Nächste Frage") }
                    }
                } else {
                    Text("Alle Fragen beantwortet. Die Wertung erfolgt erst nach ausdrücklicher Abgabe durch den Server.")
                    when (val sent = submission) {
                        null -> Button(enabled = !busy, onClick = { confirmSubmit = true }) { Text("Gewertet abgeben") }
                        is SubmissionResult.Recorded -> Text("Serverergebnis: ${sent.correct} von ${sent.total} richtig. Bitte in der Ranglistenansicht das Ergebnis bestätigen.")
                        else -> Text("Die Abgabe ist nicht bestätigt oder wurde abgelehnt. Nicht erneut abgeben; bitte in der Ranglistenansicht den Abgabestatus prüfen.")
                    }
                }
            }
        }
    }
}
