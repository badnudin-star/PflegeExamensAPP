package de.pflegeexamen.app.ui.screens.casestudies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.CaseQuestionEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.SelfEvaluation
import de.pflegeexamen.app.domain.model.SourceCitation
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CitationBadgeChip
import de.pflegeexamen.app.ui.components.CitationDetailDialog
import de.pflegeexamen.app.ui.theme.SuccessGreen
import de.pflegeexamen.app.ui.theme.WarningOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ExamTimerMode(val label: String, val initialSeconds: Int) {
    OFF("Aus", 0),
    STOPWATCH("Stoppuhr", 0),
    MIN_15("15 Min", 15 * 60),
    MIN_30("30 Min", 30 * 60),
    MIN_45("45 Min", 45 * 60)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaseStudyPracticeScreen(
    caseStudyId: Long,
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val data by repository.observeCaseStudy(caseStudyId).collectAsStateWithLifecycle(initialValue = null)

    var showVignette by remember { mutableStateOf(true) }
    var showVignetteSources by remember { mutableStateOf(false) }
    var activeCitation by remember { mutableStateOf<SourceCitation?>(null) }

    // Optionaler Examens-Timer (Standard: Aus – kein Zeitdruck!)
    var timerMode by remember { mutableStateOf(ExamTimerMode.OFF) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var timerRunning by remember { mutableStateOf(false) }
    var showTimerPicker by remember { mutableStateOf(false) }

    LaunchedEffect(timerMode, timerRunning) {
        while (timerRunning && timerMode != ExamTimerMode.OFF) {
            delay(1000L)
            if (timerMode == ExamTimerMode.STOPWATCH) {
                timerSeconds += 1
            } else if (timerSeconds > 0) {
                timerSeconds -= 1
            } else {
                timerRunning = false
            }
        }
    }

    var pendingConfirmation by remember { mutableStateOf<Triple<CaseQuestionEntity, String, Boolean>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.caseStudy?.title ?: "Fallbeispiel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(onClick = { showTimerPicker = true }) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = "Prüfungs-Timer (optional)",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        val label = when {
                            timerMode == ExamTimerMode.OFF -> "Timer"
                            else -> formatSeconds(timerSeconds)
                        }
                        Text(label)
                    }
                }
            )
        }
    ) { innerPadding ->
        val current = data
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Fallbeispiel wird geladen...")
            }
            return@Scaffold
        }

        val sortedQuestions = remember(current.questions) {
            current.questions.sortedBy { it.orderIndex }
        }
        val vignetteCitations = remember(current.caseStudy.vignetteCitationsJson) {
            repository.decodeCitations(current.caseStudy.vignetteCitationsJson)
        }
        val confirmedCount = sortedQuestions.count { it.isAnswerConfirmed }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (timerMode != ExamTimerMode.OFF) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${timerMode.label}: ${formatSeconds(timerSeconds)}" +
                                    if (timerSeconds == 0 && timerMode != ExamTimerMode.STOPWATCH) " (Zeit abgelaufen)" else "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { timerRunning = !timerRunning }) {
                                    Text(if (timerRunning) "Pause" else "Start")
                                }
                                TextButton(onClick = {
                                    timerMode = ExamTimerMode.OFF
                                    timerRunning = false
                                    timerSeconds = 0
                                }) {
                                    Text("Ausblenden")
                                }
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Fallbeschreibung",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { showVignette = !showVignette }) {
                                Icon(
                                    imageVector = if (showVignette) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = "Fallbeschreibung ein-/ausklappen"
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (current.caseStudy.categoryLabel.isNotBlank()) {
                                CategoryBadge(label = current.caseStudy.categoryLabel)
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (current.caseStudy.isExtractedOriginalCase) {
                                            "Originalfall aus Quelle"
                                        } else {
                                            "Quellen-Synthese"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "$confirmedCount / ${sortedQuestions.size} Aufgaben bestätigt",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }

                        if (showVignette) {
                            Text(
                                text = current.caseStudy.vignetteText,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            OutlinedButton(
                                onClick = { showVignetteSources = !showVignetteSources }
                            ) {
                                Text(
                                    if (showVignetteSources) {
                                        "Quellenbelege des Falls verbergen"
                                    } else {
                                        "Quellenbelege des Falls prüfen (${vignetteCitations.size})"
                                    }
                                )
                            }
                            if (showVignetteSources) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    vignetteCitations.forEach { cit ->
                                        CitationBadgeChip(
                                            citation = cit,
                                            onClick = { activeCitation = cit }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            items(sortedQuestions, key = { it.id }) { question ->
                CaseQuestionCard(
                    question = question,
                    repository = repository,
                    onDraftChange = { newText ->
                        scope.launch {
                            repository.saveDraftCaseAnswer(question.id, newText)
                        }
                    },
                    onRequestConfirmation = { answerText, onPaper ->
                        pendingConfirmation = Triple(question, answerText, onPaper)
                    },
                    onSelectEvaluation = { eval ->
                        scope.launch {
                            repository.updateCaseQuestionSelfEvaluation(question.id, eval)
                        }
                    },
                    onCitationClick = { activeCitation = it }
                )
            }
        }
    }

    if (showTimerPicker) {
        AlertDialog(
            onDismissRequest = { showTimerPicker = false },
            title = { Text("Optionaler Prüfungs-Timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Übe frei ohne Zeitdruck oder simuliere die schriftliche Examenszeit mit Countdown bzw. Stoppuhr.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ExamTimerMode.entries.forEach { mode ->
                        FilterChip(
                            selected = timerMode == mode,
                            onClick = {
                                timerMode = mode
                                timerSeconds = mode.initialSeconds
                                timerRunning = mode != ExamTimerMode.OFF
                                showTimerPicker = false
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimerPicker = false }) { Text("Schließen") }
            }
        )
    }

    pendingConfirmation?.let { (question, draftAnswer, onPaper) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("Antwort bestätigen?") },
            text = {
                Text(
                    if (onPaper && draftAnswer.isBlank()) {
                        "Du bestätigst, Aufgabe ${question.orderIndex} („${question.operatorName}“) schriftlich auf Papier bearbeitet zu haben. Danach wird die quellenbasierte Musterlösung freigeschaltet."
                    } else {
                        "Möchtest du deine Antwort zu Aufgabe ${question.orderIndex} („${question.operatorName}“) jetzt endgültig bestätigen? Erst danach werden der Kernpunkt-Abgleich und die Musterlösung sichtbar."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.confirmAndEvaluateCaseAnswer(
                                questionId = question.id,
                                finalAnswerText = draftAnswer,
                                answeredOnPaper = onPaper
                            )
                            pendingConfirmation = null
                            snackbarHostState.showSnackbar("Antwort bestätigt – Musterlösung freigeschaltet (+20 XP).")
                        }
                    }
                ) {
                    Text("Endgültig bestätigen")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("Weiter bearbeiten")
                }
            }
        )
    }

    activeCitation?.let { citation ->
        CitationDetailDialog(
            citation = citation,
            onDismiss = { activeCitation = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseQuestionCard(
    question: CaseQuestionEntity,
    repository: PflegeRepository,
    onDraftChange: (String) -> Unit,
    onRequestConfirmation: (answerText: String, onPaper: Boolean) -> Unit,
    onSelectEvaluation: (SelfEvaluation) -> Unit,
    onCitationClick: (SourceCitation) -> Unit
) {
    var answerInput by remember(question.id) { mutableStateOf(question.userAnswerText) }
    var answeredOnPaper by remember(question.id) { mutableStateOf(false) }
    var showOperatorHint by remember(question.id) { mutableStateOf(false) }

    val citations = remember(question.citationsJson) {
        repository.decodeCitations(question.citationsJson)
    }
    val matchedPoints = remember(question.matchedKeyPointsJson) {
        repository.decodeStringList(question.matchedKeyPointsJson)
    }
    val missingPoints = remember(question.missingKeyPointsJson) {
        repository.decodeStringList(question.missingKeyPointsJson)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { showOperatorHint = !showOperatorHint },
                        label = {
                            Text(
                                "Aufgabe ${question.orderIndex} · ${question.operatorName}",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                    if (question.operatorLevel.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(question.operatorLevel) })
                    }
                    AssistChip(onClick = {}, label = { Text("${question.maxPoints} P.") })
                }

                Icon(
                    imageVector = if (question.isAnswerConfirmed) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = if (question.isAnswerConfirmed) SuccessGreen else MaterialTheme.colorScheme.primary
                )
            }

            if (showOperatorHint &&
                (question.operatorHint.isNotBlank() ||
                    question.operatorGradingRubric.isNotBlank() ||
                    question.operatorCommentNotes.isNotBlank() ||
                    question.operatorCaseRelationRule.isNotBlank())
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (question.operatorCaseRelationRule.isNotBlank()) {
                            Text(
                                text = "Fallbezug: ${question.operatorCaseRelationRule}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (question.operatorHint.isNotBlank()) {
                            Text(
                                text = question.operatorHint,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (question.operatorCommentNotes.isNotBlank()) {
                            Text(
                                text = "Kommentar (K):\n${question.operatorCommentNotes}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (question.operatorGradingRubric.isNotBlank()) {
                            Text(
                                text = "Bepunktung: ${question.operatorGradingRubric}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Text(
                text = question.questionText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            if (!question.isAnswerConfirmed) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Musterlösung gesperrt: Schreibe zuerst deine Antwort oder bestätige die Bearbeitung auf Papier.",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                OutlinedTextField(
                    value = answerInput,
                    onValueChange = {
                        answerInput = it
                        onDraftChange(it)
                    },
                    label = { Text("Deine Prüfungsantwort (schriftliche Ausformulierung)") },
                    minLines = 4,
                    maxLines = 10,
                    enabled = !answeredOnPaper,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = answeredOnPaper,
                        onCheckedChange = { answeredOnPaper = it }
                    )
                    Text(
                        text = "Auf Papier / im Kopf bearbeitet (ohne Texteingabe bestätigen)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = { onRequestConfirmation(answerInput, answeredOnPaper) },
                    enabled = answeredOnPaper || answerInput.trim().length >= 8,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Antwort bestätigen & Lösung freischalten")
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Deine bestätigte Antwort:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = question.userAnswerText, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (question.totalKeyPointsCount > 0 && !question.userAnswerText.startsWith("[Schriftlich auf Papier")) {
                    val ratio = question.matchedKeyPointsCount.toFloat() / question.totalKeyPointsCount.toFloat()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Offline-Abgleich mit Quellen-Kernpunkten: ${question.matchedKeyPointsCount} von ${question.totalKeyPointsCount} getroffen",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ratio >= 0.7f) SuccessGreen else WarningOrange
                        )
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth()
                        )
                        matchedPoints.forEach { pt ->
                            Text("✓ $pt", style = MaterialTheme.typography.bodyMedium, color = SuccessGreen)
                        }
                        missingPoints.forEach { pt ->
                            Text("• Ergänzbar: $pt", style = MaterialTheme.typography.bodyMedium, color = WarningOrange)
                        }
                    }
                }

                if (question.aiFeedbackText.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Quellenbasiertes KI-Feedback",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = question.aiFeedbackText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                HorizontalDivider()

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Freigeschaltete Musterlösung (streng quellenbasiert)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = question.modelAnswerText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (citations.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                citations.forEach { cit ->
                                    CitationBadgeChip(
                                        citation = cit,
                                        onClick = { onCitationClick(cit) }
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Selbsteinschätzung:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        SelfEvaluation.COMPLETE,
                        SelfEvaluation.PARTIAL,
                        SelfEvaluation.NEEDS_REVIEW
                    ).forEach { eval ->
                        FilterChip(
                            selected = question.selfEvaluation == eval,
                            onClick = { onSelectEvaluation(eval) },
                            label = { Text(eval.displayLabel) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
