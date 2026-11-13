package de.pflegeexamen.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.ai.AiCallResult
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.data.local.StudyProfileEntity
import de.pflegeexamen.app.data.ranking.RankingSyncResult
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.domain.model.AiProviderMode
import de.pflegeexamen.app.domain.model.AiSettings
import de.pflegeexamen.app.ui.theme.SuccessGreen
import de.pflegeexamen.app.ui.theme.WarningOrange
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val categoryColorOptions = listOf(
    0xFF0E6B6EL,
    0xFF1565C0L,
    0xFF6A1B9AL,
    0xFFD84315L,
    0xFF2E7D32L,
    0xFF455A64L,
    0xFFC62828L,
    0xFF00838FL
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedSettings by repository.aiSettings.collectAsStateWithLifecycle()
    val savedRanking by repository.rankingSettings.collectAsStateWithLifecycle()
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile by repository.studyProfile.collectAsStateWithLifecycle(initialValue = StudyProfileEntity())

    var draftSettings by remember(savedSettings) { mutableStateOf(savedSettings) }
    var draftUsername by remember(savedRanking.username) { mutableStateOf(savedRanking.username) }
    var draftRankingOptIn by remember(savedRanking.rankingOptIn) { mutableStateOf(savedRanking.rankingOptIn) }
    var draftRankingUrl by remember(savedRanking.endpointUrl) { mutableStateOf(savedRanking.endpointUrl) }
    var isTestingRanking by remember { mutableStateOf(false) }
    var rankingTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var showGroqKey by remember { mutableStateOf(false) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionResultText by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var dailyGoalSlider by remember(profile?.dailyGoalPoints) {
        mutableStateOf((profile?.dailyGoalPoints ?: 20).toFloat())
    }

    var editingCategory by remember { mutableStateOf<LearningCategoryEntity?>(null) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var isImportingBundle by remember { mutableStateOf(false) }
    var bundleReportText by remember { mutableStateOf<String?>(null) }

    val importBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isImportingBundle = true
                bundleReportText = null
                runCatching { repository.importBundleZip(uri) }
                    .onSuccess { rep ->
                        isImportingBundle = false
                        val skippedInfo = if (rep.skippedSources.isNotEmpty()) {
                            "\nÜbersprungen: ${rep.skippedSources.joinToString(", ")}"
                        } else ""
                        bundleReportText = "✓ Paket „${rep.bundleName}“ eingespielt:\n" +
                            "• ${rep.sourcesImported} neue Quelle(n) indiziert\n" +
                            "• ${rep.preparationsImported} Vorbereitungsthemen\n" +
                            "• ${rep.operatorsImported} Operatoren\n" +
                            "• ${rep.categoriesImported} neue Kategorien$skippedInfo"
                        snackbarHostState.showSnackbar("Gesamtpaket importiert (${rep.sourcesImported} Quellen).")
                    }
                    .onFailure { err ->
                        isImportingBundle = false
                        bundleReportText = "✗ Import fehlgeschlagen: ${err.message}"
                    }
            }
        }
    }

    val exportBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching { repository.exportBundleZip(uri) }
                    .onSuccess { rep ->
                        snackbarHostState.showSnackbar(
                            "Backup exportiert (${rep.sourcesImported} Quellen, ${rep.preparationsImported} Themen, ${rep.operatorsImported} Operatoren)."
                        )
                    }
                    .onFailure { err ->
                        snackbarHostState.showSnackbar("Export fehlgeschlagen: ${err.message}")
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen & Gratis-KI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                val usernameError = RankingEngine.validateUsername(draftUsername)
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = WarningOrange)
                            Text("Benutzername & Stündliches Ranking (0 €)", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "Dein Pseudonym erscheint einmal pro Stunde im Community-Ranking. Es werden nur Benutzername, XP, Streak und Rang übertragen – niemals deine Quellen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = draftUsername,
                            onValueChange = { draftUsername = it.take(24) },
                            label = { Text("Benutzername (Pseudonym)") },
                            singleLine = true,
                            isError = usernameError != null,
                            supportingText = {
                                if (usernameError != null) Text(usernameError)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Eigenen XP-Stand senden", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = draftRankingOptIn,
                                onCheckedChange = { draftRankingOptIn = it }
                            )
                        }
                        OutlinedTextField(
                            value = draftRankingUrl,
                            onValueChange = { draftRankingUrl = it },
                            label = { Text("Ranking-URL (Cloudflare Worker / GitHub JSON)") },
                            placeholder = { Text("https://pflege-ranking.deinname.workers.dev") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (usernameError == null) {
                                        repository.updateRankingSettings(
                                            username = draftUsername,
                                            rankingOptIn = draftRankingOptIn,
                                            endpointUrl = draftRankingUrl
                                        )
                                        scope.launch {
                                            val res = repository.syncRanking(forceEvenIfNotDue = true)
                                            val msg = when (res) {
                                                is RankingSyncResult.Success -> "Ranking gespeichert & synchronisiert."
                                                is RankingSyncResult.NotConfigured -> "Benutzername gespeichert (noch keine Ranking-URL)."
                                                is RankingSyncResult.Failure -> "Gespeichert, Sync-Hinweis: ${res.reason}"
                                            }
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    }
                                },
                                enabled = usernameError == null
                            ) {
                                Text("Speichern & Sync")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isTestingRanking = true
                                        rankingTestResult = repository.testRankingConnection(draftRankingUrl)
                                        isTestingRanking = false
                                    }
                                },
                                enabled = draftRankingUrl.isNotBlank() && !isTestingRanking
                            ) {
                                if (isTestingRanking) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Verbindung testen")
                                }
                            }
                        }
                        rankingTestResult?.let { (ok, msg) ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) SuccessGreen else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Externes Einspeise-Paket (.pflegebundle.zip)",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = "Bereite am PC mit dem beiliegenden Tool „PflegeBundleStudio.html“ all deine PDFs/DOCX/Texte, Themenlisten, Parameter und Operatoren bequem vor – entweder per Knopfdruck hier direkt ins Handy laden oder in app/src/main/assets/seed_bundle/ fest in eine neue APK einbauen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { importBundleLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                enabled = !isImportingBundle
                            ) {
                                Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(if (isImportingBundle) "Importiere..." else ".pflegebundle.zip laden")
                            }
                            OutlinedButton(
                                onClick = { exportBundleLauncher.launch("PflegeExamen-Backup.pflegebundle.zip") }
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Alles als ZIP sichern")
                            }
                        }
                        if (isImportingBundle) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Indiziere Quellen & Parameter...", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        bundleReportText?.let { rep ->
                            Text(
                                text = rep,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (rep.startsWith("✓")) SuccessGreen else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "KI-Modus (100 % kostenlos, ohne Abo)",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = "Smartphone-KI verarbeitet Inhalte auf dem Gerät. Auf nicht unterstützten Geräten sind passende Inhalte aus einem Lernpaket für die Offline-Engine erforderlich. KI-Ergebnisse fachlich überprüfen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        AiProviderMode.entries.forEach { mode ->
                            val selected = draftSettings.mode == mode
                            Surface(
                                onClick = {
                                    draftSettings = draftSettings.copy(mode = mode)
                                    connectionResultText = null
                                },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = {
                                            draftSettings = draftSettings.copy(mode = mode)
                                            connectionResultText = null
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mode.displayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        Text(mode.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        when (draftSettings.mode) {
                            AiProviderMode.ON_DEVICE -> {
                                Text("Gemini Nano benötigt ein unterstütztes Smartphone mit AICore. Der erste Modelldownload benötigt Internet und Speicherplatz; die Generierung erfolgt auf dem Gerät. KI-Inhalte fachlich überprüfen.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { scope.launch { snackbarHostState.showSnackbar("Smartphone-KI: ${repository.smartphoneAiStatus()}") } }) { Text("Status prüfen") }
                                    OutlinedButton(onClick = { repository.downloadSmartphoneAi(); scope.launch { snackbarHostState.showSnackbar("Modelldownload angefragt. Verfügbarkeit über Status prüfen.") } }) { Text("Modell laden") }
                                }
                            }
                            AiProviderMode.OFFLINE_ONLY -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Keine Internetverbindung nötig. Lernzettel, Karteikarten, Fallbeispiele und Kernpunkt-Abgleich laufen rein auf dem Gerät.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            AiProviderMode.GROQ_FREE -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Kostenlos ohne Kreditkarte unter console.groq.com einen Free-Tier-API-Key erstellen und hier einfügen. Der Key bleibt lokal auf deinem Handy.",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = draftSettings.groqApiKey,
                                    onValueChange = { draftSettings = draftSettings.copy(groqApiKey = it) },
                                    label = { Text("Groq API-Key (gsk_...)") },
                                    singleLine = true,
                                    visualTransformation = if (showGroqKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showGroqKey = !showGroqKey }) {
                                            Icon(
                                                if (showGroqKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = "Anzeigen"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Modell wählen (Free Tier):", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b").forEach { m ->
                                        FilterChip(
                                            selected = draftSettings.groqModel == m,
                                            onClick = { draftSettings = draftSettings.copy(groqModel = m) },
                                            label = { Text(m, style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }

                            AiProviderMode.GEMINI_FREE -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.14f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(Icons.Outlined.PrivacyTip, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "Datenschutz-Hinweis: Kostenloser Key unter aistudio.google.com (ohne Kreditkarte). Laut Google können Free-Tier-Eingaben zur Modellverbesserung genutzt werden – keine echten Patientendaten eingeben!",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = draftSettings.geminiApiKey,
                                    onValueChange = { draftSettings = draftSettings.copy(geminiApiKey = it) },
                                    label = { Text("Google AI Studio Key (AIza...)") },
                                    singleLine = true,
                                    visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                            Icon(
                                                if (showGeminiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = "Anzeigen"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Gemini-Modell wählen:", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("gemini-2.5-flash", "gemini-2.5-flash-lite").forEach { m ->
                                        FilterChip(
                                            selected = draftSettings.geminiModel == m,
                                            onClick = { draftSettings = draftSettings.copy(geminiModel = m) },
                                            label = { Text(m, style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }

                            AiProviderMode.OLLAMA_LOCAL -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Läuft 100 % privat auf deinem eigenen PC/Laptop im WLAN (ollama serve mit OLLAMA_HOST=0.0.0.0). Keine Daten verlassen dein Heimnetz.",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = draftSettings.ollamaBaseUrl,
                                    onValueChange = { draftSettings = draftSettings.copy(ollamaBaseUrl = it) },
                                    label = { Text("Ollama URL im Heim-WLAN") },
                                    placeholder = { Text("http://192.168.178.20:11434") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draftSettings.ollamaModel,
                                    onValueChange = { draftSettings = draftSettings.copy(ollamaModel = it) },
                                    label = { Text("Installiertes Ollama-Modell") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    repository.updateAiSettings(draftSettings)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("KI-Einstellungen gespeichert.")
                                    }
                                }
                            ) {
                                Text("Speichern")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isTestingConnection = true
                                        connectionResultText = null
                                        repository.updateAiSettings(draftSettings)
                                        val res = repository.testAiConnection(draftSettings)
                                        isTestingConnection = false
                                        connectionResultText = when (res) {
                                            is AiCallResult.Success -> true to "✓ Verbunden (${res.providerLabel}): ${res.text}"
                                            is AiCallResult.Failure -> false to "✗ ${res.reason}"
                                        }
                                    }
                                },
                                enabled = !isTestingConnection
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Verbindung testen")
                                }
                            }
                        }

                        connectionResultText?.let { (ok, msg) ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ok) SuccessGreen else MaterialTheme.colorScheme.error
                            )
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
                        Text("Schicht-Lernziel & Streak-Schutz", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Tagesziel: ${dailyGoalSlider.roundToInt()} Aktivitätspunkte (z. B. 4 Karteikarten + 1 Fallfrage). " +
                                "Du besitzt bis zu 2 automatisch regenerierende „Frei-Tage“, damit Frühdienst/Spätdienst deinen Lern-Streak nicht zerstören.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = dailyGoalSlider,
                            onValueChange = { dailyGoalSlider = it },
                            valueRange = 10f..60f,
                            steps = 9
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        repository.updateDailyGoal(dailyGoalSlider.roundToInt())
                                        snackbarHostState.showSnackbar("Tagesziel auf ${dailyGoalSlider.roundToInt()} Punkte gesetzt.")
                                    }
                                }
                            ) {
                                Text("Tagesziel speichern")
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Lernkategorien & Kompetenzbereiche", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Vorbefüllt mit den 5 Kompetenzbereichen (PflAPrV) und Kernfächern. Du kannst jederzeit eigene Kategorien anlegen, umbenennen oder entfernen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { showNewCategoryDialog = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Kategorie anlegen")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val added = repository.restoreMissingDefaultCategories()
                                        snackbarHostState.showSnackbar(
                                            if (added > 0) "$added Standard-Kategorie(n) ergänzt." else "Alle Standard-Kategorien sind bereits vorhanden."
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Standards ergänzen")
                            }
                        }
                    }
                }
            }

            items(categories, key = { it.id }) { cat ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(cat.colorHex))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${cat.shortCode} · ${cat.name}", style = MaterialTheme.typography.titleSmall)
                            if (cat.description.isNotBlank()) {
                                Text(
                                    cat.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { editingCategory = cat }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    repository.deleteCategory(cat)
                                    snackbarHostState.showSnackbar("Kategorie „${cat.name}“ gelöscht.")
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Plattform-Architektur (Android + iOS-bereit)", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            text = "Kernlogik (Quellen-Retrieval, Spaced Repetition, Gamification, Prompt-Builder & JSON-Parser) liegt als reines Kotlin-Domain-Paket vor – vorbereitet für spätere gemeinsame Nutzung per Kotlin Multiplatform (inkl. Apple Foundation Models ab iOS 26).",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showNewCategoryDialog || editingCategory != null) {
        CategoryEditorDialog(
            initial = editingCategory,
            onDismiss = {
                showNewCategoryDialog = false
                editingCategory = null
            },
            onSave = { cat ->
                scope.launch {
                    repository.saveCategory(cat)
                    showNewCategoryDialog = false
                    editingCategory = null
                    snackbarHostState.showSnackbar("Kategorie „${cat.name}“ gespeichert.")
                }
            }
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    initial: LearningCategoryEntity?,
    onDismiss: () -> Unit,
    onSave: (LearningCategoryEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var shortCode by remember { mutableStateOf(initial?.shortCode.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var colorHex by remember { mutableStateOf(initial?.colorHex ?: categoryColorOptions.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Kategorie anlegen" else "Kategorie bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name * (z. B. KB I – Pflegeprozesse)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = shortCode,
                    onValueChange = { shortCode = it },
                    label = { Text("Kürzel (z. B. KB I)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Kurzbeschreibung (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Farbmarkierung:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    categoryColorOptions.forEach { hex ->
                        val isSelected = colorHex == hex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                )
                                .clickable { colorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: LearningCategoryEntity(
                            name = name.trim(),
                            shortCode = shortCode.trim()
                        )).copy(
                            name = name.trim(),
                            shortCode = shortCode.trim().ifBlank { name.trim().take(6) },
                            description = description.trim(),
                            colorHex = colorHex
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
