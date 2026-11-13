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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prepItems by repository.preparationItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by repository.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedCategoryFilter by remember { mutableStateOf<Long?>(null) }
    var selectedExamDayFilter by remember { mutableStateOf("Alle") }
    var searchQuery by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<PreparationItemEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationSummaryDialog by remember { mutableStateOf<String?>(null) }

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

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    }.orEmpty()
                    val count = repository.importPreparationListJson(raw)
                    snackbarHostState.showSnackbar("$count Themen in Vorbereitungsliste importiert.")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("JSON-Import fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = repository.exportPreparationListJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    snackbarHostState.showSnackbar("Vorbereitungsliste als JSON exportiert.")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Export fehlgeschlagen: ${e.message}")
                }
            }
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
                        text = "Alle 42 Prüfungsthemen sind nach den 3 schriftlichen Prüfungstagen und Kompetenzschwerpunkten (inkl. Anatomie & Physiologie bei Krankheitsbildern) voreingestellt – frei bearbeitbar und bereit für deine Quellen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (sources.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.14f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Outlined.Info, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Noch keine eigenen Quellen verknüpft: Füge im Tab „Quellen“ deine PDFs, Skripte, Word-Dateien oder Buchfotos (oder über Einstellungen ein .pflegebundle.zip) hinzu.",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Thema anlegen")
                        }

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

                        OutlinedButton(onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                            Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("JSON Import")
                        }

                        if (prepItems.isNotEmpty()) {
                            OutlinedButton(onClick = { exportJsonLauncher.launch("vorbereitungsliste_2027.json") }) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Export")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val added = repository.restoreMissingDefaultPreparations()
                                    snackbarHostState.showSnackbar(
                                        if (added > 0) "$added Themen der Liste Frühjahr 2027 ergänzt."
                                        else "Alle 42 Themen der Liste Frühjahr 2027 sind bereits vorhanden."
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Liste 2027 ergänzen")
                        }
                    }

                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Durchsuche eigene Quellen und erstelle Lernzettel & Karteikarten...",
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
                            categories = categories,
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
                        "Lade die 42 offiziellen Themen für Frühjahr 2027 oder lege eigene Themen mit Parametern an."
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
                    onEdit = { editingItem = item },
                    onDelete = {
                        scope.launch {
                            repository.deletePreparationItem(item)
                            snackbarHostState.showSnackbar("Thema „${item.title}“ gelöscht.")
                        }
                    },
                    onToggleComplete = { checked ->
                        scope.launch {
                            repository.savePreparationItem(item.copy(isCompleted = checked))
                        }
                    }
                )
            }
        }
    }

    if (showCreateDialog || editingItem != null) {
        PreparationItemEditorDialog(
            initial = editingItem,
            availableSources = sources,
            categories = categories,
            onDismiss = {
                showCreateDialog = false
                editingItem = null
            },
            onSave = { savedItem ->
                scope.launch {
                    repository.savePreparationItem(savedItem)
                    showCreateDialog = false
                    editingItem = null
                    snackbarHostState.showSnackbar("Thema „${savedItem.title}“ gespeichert.")
                }
            }
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasAnySources) "Ziel: ${item.targetFlashcardCount} Karten" else "Wartet auf eigene Quellen",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasAnySources) MaterialTheme.colorScheme.primary else WarningOrange,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreparationItemEditorDialog(
    initial: PreparationItemEntity?,
    availableSources: List<SourceDocumentEntity>,
    categories: List<LearningCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (PreparationItemEntity) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var examDayTag by remember { mutableStateOf(initial?.examDayTag.orEmpty()) }
    var competenceCodes by remember { mutableStateOf(initial?.competenceFocusCodes.orEmpty()) }
    var selectedCategory by remember {
        mutableStateOf(categories.firstOrNull { it.id == initial?.categoryId })
    }
    var category by remember { mutableStateOf(initial?.categoryOrCompetenceArea.orEmpty()) }
    var keywords by remember { mutableStateOf(initial?.keywordsCsv.orEmpty()) }
    var depth by remember { mutableStateOf(initial?.detailDepth ?: DetailDepth.STANDARD) }
    var priority by remember { mutableStateOf(initial?.priority ?: PriorityLevel.MEDIUM) }
    var customNote by remember { mutableStateOf(initial?.customPromptNote.orEmpty()) }
    var targetCardCount by remember { mutableStateOf((initial?.targetFlashcardCount ?: 10).toFloat()) }

    val selectedParams = remember {
        mutableStateListOf<String>().apply {
            val existing = initial?.focusParametersCsv?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            addAll(existing)
        }
    }
    var newCustomParam by remember { mutableStateOf("") }

    val selectedCardTypes = remember {
        mutableStateListOf<FlashcardType>().apply {
            val existing = initial?.flashcardTypesCsv?.split(",")
                ?.mapNotNull { runCatching { FlashcardType.valueOf(it.trim()) }.getOrNull() }
                .orEmpty()
            if (existing.isNotEmpty()) addAll(existing) else addAll(FlashcardType.entries)
        }
    }

    val selectedSourceIds = remember {
        mutableStateListOf<Long>().apply {
            val existing = initial?.selectedSourceIdsCsv?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty()
            addAll(existing)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Vorbereitungsthema anlegen" else "Thema bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Thema / Titel *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Prüfungstag:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("", "Tag 1", "Tag 2", "Tag 3").forEach { day ->
                        FilterChip(
                            selected = examDayTag == day,
                            onClick = { examDayTag = day },
                            label = { Text(if (day.isBlank()) "Ohne Tag" else day) }
                        )
                    }
                }

                OutlinedTextField(
                    value = competenceCodes,
                    onValueChange = { competenceCodes = it },
                    label = { Text("Kompetenzschwerpunkte (z. B. I.1, I.5, II.1, IV)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (categories.isNotEmpty()) {
                    Text("Kategorie:", style = MaterialTheme.typography.labelLarge)
                    CategorySelectorChips(
                        categories = categories,
                        selectedCategoryId = selectedCategory?.id,
                        onSelect = { cat ->
                            selectedCategory = cat
                            category = cat?.name ?: ""
                        }
                    )
                }

                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Suchbegriffe / Synonyme (kommagetrennt)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Priorität", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriorityLevel.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.displayLabel) }
                        )
                    }
                }

                Text("Detailtiefe für Lernzettel", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailDepth.entries.forEach { d ->
                        FilterChip(
                            selected = depth == d,
                            onClick = { depth = d },
                            label = { Text(d.displayLabel) }
                        )
                    }
                }

                Text("Fokus-Parameter (inkl. Anatomie & Physiologie bei Krankheitsbildern)", style = MaterialTheme.typography.labelLarge)
                val allParamChips = (ParameterSuggestions.suggestedFocusParameters + selectedParams).distinct()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allParamChips.forEach { param ->
                        val isSelected = param in selectedParams
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedParams.remove(param) else selectedParams.add(param)
                            },
                            label = { Text(param, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newCustomParam,
                        onValueChange = { newCustomParam = it },
                        label = { Text("Eigener Parameter") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val trimmed = newCustomParam.trim()
                            if (trimmed.isNotBlank() && trimmed !in selectedParams) {
                                selectedParams.add(trimmed)
                                newCustomParam = ""
                            }
                        }
                    ) {
                        Text("+")
                    }
                }

                Text(
                    "Maximale Karteikarten pro Lauf: ${targetCardCount.roundToInt()}",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = targetCardCount,
                    onValueChange = { targetCardCount = it },
                    valueRange = 3f..30f,
                    steps = 26
                )

                Text("Gewünschte Karteikarten-Typen", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlashcardType.entries.forEach { type ->
                        val active = type in selectedCardTypes
                        FilterChip(
                            selected = active,
                            onClick = {
                                if (active && selectedCardTypes.size > 1) {
                                    selectedCardTypes.remove(type)
                                } else if (!active) {
                                    selectedCardTypes.add(type)
                                }
                            },
                            label = { Text(type.displayLabel, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                if (availableSources.isNotEmpty()) {
                    Text(
                        "Quellen einschränken (leer = alle ${availableSources.size} Quellen)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    availableSources.forEach { src ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = src.id in selectedSourceIds,
                                onCheckedChange = { checked ->
                                    if (checked) selectedSourceIds.add(src.id) else selectedSourceIds.remove(src.id)
                                }
                            )
                            Text(src.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = customNote,
                    onValueChange = { customNote = it },
                    label = { Text("Zusätzliche Filterwörter / Fokus-Hinweis (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entity = (initial ?: PreparationItemEntity(title = title.trim())).copy(
                        title = title.trim(),
                        categoryId = selectedCategory?.id,
                        categoryOrCompetenceArea = category.trim().ifBlank { selectedCategory?.name ?: examDayTag },
                        examDayTag = examDayTag.trim(),
                        competenceFocusCodes = competenceCodes.trim(),
                        keywordsCsv = keywords.trim(),
                        detailDepth = depth,
                        priority = priority,
                        focusParametersCsv = selectedParams.joinToString(", "),
                        targetFlashcardCount = targetCardCount.roundToInt(),
                        flashcardTypesCsv = selectedCardTypes.joinToString(",") { it.name },
                        selectedSourceIdsCsv = selectedSourceIds.joinToString(","),
                        customPromptNote = customNote.trim()
                    )
                    onSave(entity)
                },
                enabled = title.isNotBlank()
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
