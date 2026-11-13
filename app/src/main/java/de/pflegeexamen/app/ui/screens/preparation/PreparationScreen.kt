package de.pflegeexamen.app.ui.screens.preparation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.data.local.PreparationItemEntity
import de.pflegeexamen.app.data.local.SourceDocumentEntity
import de.pflegeexamen.app.data.repository.GenerationReport
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.ParameterSuggestions
import de.pflegeexamen.app.domain.model.PriorityLevel
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CategoryFilterBar
import de.pflegeexamen.app.ui.components.CategorySelectorChips
import de.pflegeexamen.app.ui.components.EmptyStateCard
import de.pflegeexamen.app.ui.theme.WarningOrange
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreparationScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    initialSubTab: Int = 0
) {
    var selectedSubTab by remember { mutableIntStateOf(initialSubTab) }
    LaunchedEffect(initialSubTab) {
        selectedSubTab = initialSubTab
    }

    val studyNotes by repository.studyNotes.collectAsStateWithLifecycle(initialValue = emptyList())
    val dueCount by repository.observeDueFlashcardCount().collectAsStateWithLifecycle(initialValue = 0)

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedSubTab) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { Text("Vorbereitung") }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { Text("Lernzettel (${studyNotes.size})") }
            )
            Tab(
                selected = selectedSubTab == 2,
                onClick = { selectedSubTab = 2 },
                text = { Text("Karten ($dueCount fällig)") }
            )
        }

        when (selectedSubTab) {
            0 -> PreparationListTab(
                repository = repository,
                snackbarHostState = snackbarHostState,
                onOpenStudyNotes = { selectedSubTab = 1 }
            )
            1 -> StudyNotesTab(
                repository = repository,
                snackbarHostState = snackbarHostState
            )
            2 -> FlashcardsTab(
                repository = repository,
                snackbarHostState = snackbarHostState
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreparationListTab(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    onOpenStudyNotes: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prepItems by repository.preparationItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by repository.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedCategoryFilter by remember { mutableStateOf<Long?>(null) }
    var selectedExamDayFilter by remember { mutableStateOf("Alle") }
    var searchQuery by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationSummaryDialog by remember { mutableStateOf<String?>(null) }
    var inspectingTopic by remember { mutableStateOf<PreparationItemEntity?>(null) }

    val filteredItems = remember(prepItems, selectedCategoryFilter, selectedExamDayFilter, searchQuery) {
        prepItems.filter { item ->
            val catOk = selectedCategoryFilter == null || item.categoryId == selectedCategoryFilter
            val dayOk = selectedExamDayFilter == "Alle" ||
                item.examDayTag.equals(selectedExamDayFilter, ignoreCase = true)
            val queryOk = searchQuery.isBlank() ||
                item.title.contains(searchQuery.trim(), ignoreCase = true) ||
                item.keywordsCsv.contains(searchQuery.trim(), ignoreCase = true) ||
                item.competenceFocusCodes.contains(searchQuery.trim(), ignoreCase = true)
            catOk && dayOk && queryOk
        }
    }

    fun runGenerationFor(itemsToProcess: List<PreparationItemEntity>) {
        if (itemsToProcess.isEmpty()) return
        scope.launch {
            isGenerating = true
            val lines = mutableListOf<String>()
            var anySuccess = false
            for (item in itemsToProcess) {
                when (val report = repository.generateFromPreparationItem(item)) {
                    is GenerationReport.Success -> {
                        anySuccess = true
                        lines.add(
                            "✓ ${item.title} [${report.engineUsedLabel}]:\n   Lernzettel erstellt (${report.matchedPassageCount} Quellenabschnitte) & ${report.flashcardCount} Karteikarten (+10 XP)." +
                                (report.infoHint?.let { "\n   $it" } ?: "")
                        )
                    }
                    is GenerationReport.NoEvidenceFound -> {
                        lines.add("✗ ${item.title}: ${report.message}")
                    }
                }
            }
            isGenerating = false
            generationSummaryDialog = lines.joinToString("\n\n")
            if (anySuccess) {
                snackbarHostState.showSnackbar("Generierung abgeschlossen (+10 XP).")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Vorbereitungsliste Frühjahr 2027 (Tag 1–3)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Alle 42 Prüfungsthemen sind nach den 3 schriftlichen Prüfungstagen und Kompetenzschwerpunkten (inkl. Anatomie & Physiologie bei Krankheitsbildern) voreingestellt – wähle ein Thema und starte mit dem Lernen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (filteredItems.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { runGenerationFor(filteredItems) },
                                enabled = !isGenerating
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Sichtbare generieren (${filteredItems.size})")
                            }
                        }


                    }

                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Erstelle Lernzettel und Karteikarten...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (prepItems.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Themen, Kompetenzschwerpunkt (z. B. I.2) oder Keyword suchen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Alle", "Tag 1", "Tag 2", "Tag 3").forEach { day ->
                            val count = if (day == "Alle") prepItems.size
                            else prepItems.count { it.examDayTag.equals(day, ignoreCase = true) }
                            FilterChip(
                                selected = selectedExamDayFilter == day,
                                onClick = { selectedExamDayFilter = day },
                                label = { Text("$day ($count)") }
                            )
                        }
                    }
                    if (categories.isNotEmpty()) {
                        CategoryFilterBar(
                            categories = categories.filterNot { it.shortCode.equals("Ana/Phys", true) },
                            selectedCategoryId = selectedCategoryFilter,
                            onSelectCategory = { selectedCategoryFilter = it }
                        )
                    }
                }
            }
        }

        if (filteredItems.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                    title = if (prepItems.isEmpty()) "Vorbereitungsliste ist noch leer" else "Keine Themen für diesen Filter",
                    description = if (prepItems.isEmpty()) {
                        "Lade die fest vorgegebenen Themen für Frühjahr 2027."
                    } else {
                        "Passe Suchbegriff, Prüfungstag oder Kategorie an."
                    },
                    actionLabel = if (prepItems.isEmpty()) "Liste Frühjahr 2027 laden" else null,
                    onAction = if (prepItems.isEmpty()) {
                        {
                            scope.launch {
                                val count = repository.restoreMissingDefaultPreparations()
                                snackbarHostState.showSnackbar("$count Themen geladen.")
                            }
                        }
                    } else null
                )
            }
        } else {
            items(filteredItems, key = { it.id }) { item ->
                val catColor = categories.firstOrNull { it.id == item.categoryId }?.colorHex
                PreparationItemCard(
                    item = item,
                    categoryColorHex = catColor,
                    hasAnySources = sources.isNotEmpty(),
                    isGenerating = isGenerating,
                    onGenerate = { runGenerationFor(listOf(item)) },
                    onShowSources = { inspectingTopic = item },
                    onToggleComplete = { checked ->
                        scope.launch {
                            repository.savePreparationItem(item.copy(isCompleted = checked))
                        }
                    }
                )
            }
        }
    }

    inspectingTopic?.let { topic ->
        TopicPassagesDialog(
            item = topic,
            repository = repository,
            sourceVersion = sources,
            onDismiss = { inspectingTopic = null }
        )
    }

    generationSummaryDialog?.let { summary ->
        AlertDialog(
            onDismissRequest = { generationSummaryDialog = null },
            title = { Text("Ergebnis der Erstellung") },
            text = {
                Text(
                    text = summary,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    generationSummaryDialog = null
                    onOpenStudyNotes()
                }) {
                    Text("Zu den Lernzetteln")
                }
            },
            dismissButton = {
                TextButton(onClick = { generationSummaryDialog = null }) {
                    Text("Schließen")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreparationItemCard(
    item: PreparationItemEntity,
    categoryColorHex: Long?,
    hasAnySources: Boolean,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onShowSources: () -> Unit,
    onToggleComplete: (Boolean) -> Unit
) {
    val params = remember(item.focusParametersCsv) {
        item.focusParametersCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    val includesAnatomy = remember(item.focusParametersCsv) {
        item.focusParametersCsv.contains("Anatomie", ignoreCase = true)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isCompleted,
                    onCheckedChange = onToggleComplete
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (item.examDayTag.isNotBlank()) {
                            CategoryBadge(label = item.examDayTag, colorHex = categoryColorHex)
                        } else if (item.categoryOrCompetenceArea.isNotBlank()) {
                            CategoryBadge(label = item.categoryOrCompetenceArea, colorHex = categoryColorHex)
                        }
                        if (item.competenceFocusCodes.isNotBlank()) {
                            AssistChip(
                                onClick = {},
                                label = { Text("KS: ${item.competenceFocusCodes}", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (includesAnatomy) {
                            AssistChip(
                                onClick = {},
                                label = { Text("inkl. Anatomie & Physiologie", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(
                        text = "${item.priority.displayLabel} · ${item.detailDepth.displayLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }

            if (item.keywordsCsv.isNotBlank()) {
                Text(
                    text = "Suchbegriffe: ${item.keywordsCsv}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (params.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    params.forEach { param ->
                        AssistChip(
                            onClick = {},
                            label = { Text(param, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            if (item.customPromptNote.isNotBlank()) {
                Text(
                    text = item.customPromptNote,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedButton(onClick = onShowSources) {
                Text("Passende Textstellen ansehen")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ziel: ${item.targetFlashcardCount} Karten",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                FilledTonalButton(
                    onClick = onGenerate,
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Lernzettel & Karten")
                }
            }
        }
    }
}

@Composable
private fun TopicPassagesDialog(
    item: PreparationItemEntity,
    repository: PflegeRepository,
    sourceVersion: List<SourceDocumentEntity>,
    onDismiss: () -> Unit
) {
    var passages by remember(item.id) { mutableStateOf<List<de.pflegeexamen.app.domain.model.RetrievedPassage>?>(null) }
    var loadError by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id, sourceVersion) {
        passages = null
        loadError = null
        try {
            passages = repository.findTopicPassages(item)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Die Textstellen konnten nicht geladen werden."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Textstellen: ${item.title}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Die besten automatisch gefundenen Textstellen aus deinen Quellen. Bitte Relevanz und OCR-Erkennung prüfen; die Originaldateien werden nicht verändert.", style = MaterialTheme.typography.bodySmall)
                when {
                    loadError != null -> Text(loadError!!)
                    passages == null -> CircularProgressIndicator()
                    passages!!.isEmpty() -> Text("Keine passende Textstelle gefunden. Importiere weitere Quellen oder prüfe, ob die Quelle lesbaren Text enthält.")
                    else -> passages!!.forEach { passage ->
                        val chunk = passage.chunk
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(listOf(chunk.sourceTitle, chunk.pageLabel(), chunk.sectionHeading).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                                Text(chunk.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}
