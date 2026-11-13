package de.pflegeexamen.app.ui.screens.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.ingestion.OcrProcessor
import de.pflegeexamen.app.data.ingestion.ParsedImportResult
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.data.local.SourceDocumentEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.SourceType
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CategoryFilterBar
import de.pflegeexamen.app.ui.components.CategorySelectorChips
import de.pflegeexamen.app.ui.components.EmptyStateCard
import de.pflegeexamen.app.ui.components.OfflineSourceBanner
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSettings by repository.aiSettings.collectAsStateWithLifecycle()
    val sources by repository.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedCategoryFilter by remember { mutableStateOf<Long?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingLabel by remember { mutableStateOf("") }

    var pendingFileImport by remember { mutableStateOf<ParsedImportResult?>(null) }
    var showManualNoteDialog by remember { mutableStateOf(false) }
    var ocrPagesBuffer by remember { mutableStateOf<List<String>?>(null) }
    var inspectingSource by remember { mutableStateOf<SourceDocumentEntity?>(null) }
    var editingSource by remember { mutableStateOf<SourceDocumentEntity?>(null) }
    var deletingSource by remember { mutableStateOf<SourceDocumentEntity?>(null) }

    val ocrProcessor = remember(context) { OcrProcessor(context) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val filteredSources = remember(sources, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) sources
        else sources.filter { it.categoryId == selectedCategoryFilter }
    }

    fun handleImportedUri(uri: Uri) {
        scope.launch {
            isProcessing = true
            processingLabel = "Dokument wird offline analysiert & indexiert..."
            try {
                val parsed = repository.ingestionService.importDocumentFromUri(uri)
                if (parsed.sourceType == SourceType.IMAGE_OCR) {
                    val existing = ocrPagesBuffer.orEmpty()
                    val text = parsed.pages.firstOrNull()?.text.orEmpty()
                    if (text.isBlank()) {
                        snackbarHostState.showSnackbar("Kein Text auf dem Bild erkannt.")
                    } else {
                        ocrPagesBuffer = existing + text
                    }
                } else {
                    pendingFileImport = parsed
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    e.message ?: "Fehler beim Importieren der Datei."
                )
            } finally {
                isProcessing = false
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handleImportedUri(uri)
    }

    val galleryImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isProcessing = true
                processingLabel = "Offline-OCR erkennt Text auf Foto..."
                try {
                    val text = ocrProcessor.recognizeFromUri(uri)
                    if (text.isBlank()) {
                        snackbarHostState.showSnackbar("Es konnte kein lesbarer Text im Foto erkannt werden.")
                    } else {
                        ocrPagesBuffer = ocrPagesBuffer.orEmpty() + text
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("OCR-Fehler: ${e.message}")
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            scope.launch {
                isProcessing = true
                processingLabel = "Offline-OCR liest abfotografierte Seite..."
                try {
                    val text = ocrProcessor.recognizeFromUri(uri)
                    if (text.isBlank()) {
                        snackbarHostState.showSnackbar("Kein Text auf der Aufnahme erkannt – bitte bei gutem Licht wiederholen.")
                    } else {
                        ocrPagesBuffer = ocrPagesBuffer.orEmpty() + text
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("OCR-Fehler: ${e.message}")
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    fun launchCameraCapture() {
        val uri = createTempCaptureUri(context)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Kameraberechtigung wird für den Buchseiten-Scan benötigt.")
            }
        }
    }

    fun requestCameraScan() {
        val status = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (status == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Eigene Quellen",
                    style = MaterialTheme.typography.headlineSmall
                )
                OfflineSourceBanner(
                    aiSettings = aiSettings,
                    text = "Füge ausschließlich deine eigenen Lehrbuchauszüge, Skripte, PDFs, Word-Dateien oder Buchseiten-Fotos hinzu."
                )
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Quelle hinzufügen",
                        style = MaterialTheme.typography.titleMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/plain",
                                        "text/markdown",
                                        "image/*"
                                    )
                                )
                            }
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("PDF / DOCX / TXT")
                        }

                        FilledTonalButton(onClick = { requestCameraScan() }) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Kamera-OCR")
                        }

                        FilledTonalButton(onClick = { galleryImageLauncher.launch("image/*") }) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Foto-OCR")
                        }

                        OutlinedButton(onClick = { showManualNoteDialog = true }) {
                            Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Text / Notiz")
                        }
                    }

                    if (isProcessing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = processingLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (sources.isNotEmpty() && categories.isNotEmpty()) {
            item {
                CategoryFilterBar(
                    categories = categories,
                    selectedCategoryId = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it }
                )
            }
        }

        if (filteredSources.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.FolderOpen,
                    title = if (sources.isEmpty()) "Noch keine eigenen Quellen hinterlegt" else "Keine Quellen in dieser Kategorie",
                    description = if (sources.isEmpty()) {
                        "Importiere deine eigenen Unterrichtsskripte (PDF/Word), Mitschriften (TXT/MD) oder scanne Buchseiten per Offline-OCR ab."
                    } else {
                        "Wähle „Alle Bereiche“ oder ordne dieser Kategorie eine Quelle zu."
                    },
                    actionLabel = if (sources.isEmpty()) "Datei auswählen" else null,
                    onAction = if (sources.isEmpty()) {
                        {
                            documentPickerLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "text/plain",
                                    "text/markdown",
                                    "image/*"
                                )
                            )
                        }
                    } else null
                )
            }
        } else {
            items(filteredSources, key = { it.id }) { source ->
                val catColor = categories.firstOrNull { it.id == source.categoryId }?.colorHex
                SourceItemCard(
                    source = source,
                    categoryColorHex = catColor,
                    onInspect = { inspectingSource = source },
                    onEdit = { editingSource = source },
                    onDelete = { deletingSource = source }
                )
            }
        }
    }

    pendingFileImport?.let { parsed ->
        ImportConfirmDialog(
            parsed = parsed,
            categories = categories,
            onDismiss = { pendingFileImport = null },
            onSave = { title, tags, cat ->
                scope.launch {
                    try {
                        repository.saveParsedImport(
                            title = title,
                            tags = tags,
                            parsed = parsed,
                            categoryId = cat?.id,
                            categoryLabel = cat?.shortCode ?: cat?.name ?: ""
                        )
                        pendingFileImport = null
                        val extra = if (parsed.usedOcrFallbackForPdf) " (inkl. automatischer OCR)" else ""
                        snackbarHostState.showSnackbar("Quelle „$title“ gespeichert$extra (+15 XP).")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Speichern fehlgeschlagen.")
                    }
                }
            }
        )
    }

    ocrPagesBuffer?.let { pages ->
        OcrReviewDialog(
            initialPages = pages,
            categories = categories,
            onAddAnotherCameraPage = { requestCameraScan() },
            onAddAnotherGalleryPage = { galleryImageLauncher.launch("image/*") },
            onDismiss = { ocrPagesBuffer = null },
            onSave = { title, tags, cat, editedPages ->
                scope.launch {
                    try {
                        val rawPages = editedPages.mapIndexedNotNull { idx, text ->
                            val clean = text.trim()
                            if (clean.isBlank()) null else RawPage(pageNumber = idx + 1, text = clean)
                        }
                        repository.saveRawPagesAsSource(
                            title = title,
                            tags = tags,
                            sourceType = SourceType.IMAGE_OCR,
                            originalFileName = null,
                            pages = rawPages,
                            categoryId = cat?.id,
                            categoryLabel = cat?.shortCode ?: cat?.name ?: ""
                        )
                        ocrPagesBuffer = null
                        snackbarHostState.showSnackbar("Foto-Scan „$title“ (${rawPages.size} Seite/n) gespeichert (+15 XP).")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Speichern fehlgeschlagen.")
                    }
                }
            }
        )
    }

    if (showManualNoteDialog) {
        ManualSourceDialog(
            categories = categories,
            onDismiss = { showManualNoteDialog = false },
            onSave = { title, tags, cat, bodyText ->
                scope.launch {
                    try {
                        val pages = repository.ingestionService.splitPlainTextToLogicalSections(bodyText)
                        repository.saveRawPagesAsSource(
                            title = title,
                            tags = tags,
                            sourceType = SourceType.MANUAL_NOTE,
                            originalFileName = null,
                            pages = pages,
                            categoryId = cat?.id,
                            categoryLabel = cat?.shortCode ?: cat?.name ?: ""
                        )
                        showManualNoteDialog = false
                        snackbarHostState.showSnackbar("Eigene Notiz „$title“ indexiert (+15 XP).")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Text zu kurz oder ungültig.")
                    }
                }
            }
        )
    }

    inspectingSource?.let { source ->
        SourceChunksDialog(
            source = source,
            repository = repository,
            onDismiss = { inspectingSource = null }
        )
    }

    editingSource?.let { source ->
        EditSourceMetaDialog(
            source = source,
            categories = categories,
            onDismiss = { editingSource = null },
            onSave = { newTitle, newTags, cat ->
                scope.launch {
                    repository.updateSourceMeta(
                        id = source.id,
                        title = newTitle,
                        tags = newTags,
                        categoryId = cat?.id,
                        categoryLabel = cat?.shortCode ?: cat?.name ?: ""
                    )
                    editingSource = null
                }
            }
        )
    }

    deletingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deletingSource = null },
            title = { Text("Quelle löschen?") },
            text = {
                Text("Soll „${source.title}“ inklusive aller indexierten Textabschnitte entfernt werden? Bereits erstellte Lernzettel bleiben erhalten.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.deleteSource(source)
                            deletingSource = null
                            snackbarHostState.showSnackbar("Quelle gelöscht.")
                        }
                    }
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSource = null }) { Text("Abbrechen") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceItemCard(
    source: SourceDocumentEntity,
    categoryColorHex: Long?,
    onInspect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(source.updatedAt) {
        SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN).format(Date(source.updatedAt))
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = source.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (source.categoryLabel.isNotBlank()) {
                            CategoryBadge(label = source.categoryLabel, colorHex = categoryColorHex)
                        }
                        AssistChip(
                            onClick = onInspect,
                            label = { Text(source.sourceType.displayLabel, style = MaterialTheme.typography.labelMedium) }
                        )
                        AssistChip(
                            onClick = onInspect,
                            label = {
                                Text(
                                    "${source.totalPagesOrSections} Abschn./Seiten · ${source.characterCount} Zeichen",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
                Row {
                    IconButton(onClick = onInspect) {
                        Icon(Icons.Outlined.Visibility, contentDescription = "Inhalt ansehen")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                    }
                }
            }
            if (source.tags.isNotBlank()) {
                Text(
                    text = "Schlagworte: ${source.tags}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Hinzugefügt/Aktualisiert: $dateStr" +
                    (source.originalFileName?.let { " · Datei: $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImportConfirmDialog(
    parsed: ParsedImportResult,
    categories: List<LearningCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, tags: String, category: LearningCategoryEntity?) -> Unit
) {
    var title by remember { mutableStateOf(parsed.suggestedTitle) }
    var tags by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<LearningCategoryEntity?>(null) }
    val preview = remember(parsed) {
        parsed.pages.firstOrNull()?.text?.take(320).orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importierte Quelle speichern") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Typ: ${parsed.sourceType.displayLabel} · ${parsed.pages.size} Seite(n)/Abschnitt(e)" +
                        if (parsed.usedOcrFallbackForPdf) " (Scan-PDF per OCR erkannt)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Quellentitel (für Belegangaben)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Schlagworte (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    Text("Kategorie / Kompetenzbereich:", style = MaterialTheme.typography.labelLarge)
                    CategorySelectorChips(
                        categories = categories,
                        selectedCategoryId = selectedCategory?.id,
                        onSelect = { selectedCategory = it }
                    )
                }
                if (preview.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Text(
                            text = "Vorschau:\n$preview…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.ifBlank { parsed.suggestedTitle }, tags, selectedCategory) },
                enabled = parsed.pages.isNotEmpty()
            ) {
                Text("Quelle indexieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun OcrReviewDialog(
    initialPages: List<String>,
    categories: List<LearningCategoryEntity>,
    onAddAnotherCameraPage: () -> Unit,
    onAddAnotherGalleryPage: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (title: String, tags: String, category: LearningCategoryEntity?, pages: List<String>) -> Unit
) {
    var title by remember { mutableStateOf("Buchscan ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())}") }
    var tags by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<LearningCategoryEntity?>(null) }
    val editedPages = remember(initialPages.size) {
        mutableStateListOf<String>().apply { addAll(initialPages) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Foto-OCR prüfen & speichern (${editedPages.size} Seite/n)") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Quellentitel (z. B. PflegeHeute S. 142–143)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Schlagworte (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    Text("Kategorie / Kompetenzbereich:", style = MaterialTheme.typography.labelLarge)
                    CategorySelectorChips(
                        categories = categories,
                        selectedCategoryId = selectedCategory?.id,
                        onSelect = { selectedCategory = it }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddAnotherCameraPage) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Kamera-Seite")
                    }
                    OutlinedButton(onClick = onAddAnotherGalleryPage) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Foto-Seite")
                    }
                }
                editedPages.forEachIndexed { index, pageText ->
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { editedPages[index] = it },
                        label = { Text("Erkannter Text – Scan #${index + 1} (editierbar)") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.ifBlank { "Foto-Scan" }, tags, selectedCategory, editedPages.toList()) },
                enabled = editedPages.any { it.isNotBlank() }
            ) {
                Text("Als Quelle speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Verwerfen") }
        }
    )
}

@Composable
private fun ManualSourceDialog(
    categories: List<LearningCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, tags: String, category: LearningCategoryEntity?, body: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<LearningCategoryEntity?>(null) }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eigene Notiz / Textquelle") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel der Quelle *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Schlagworte (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    Text("Kategorie / Kompetenzbereich:", style = MaterialTheme.typography.labelLarge)
                    CategorySelectorChips(
                        categories = categories,
                        selectedCategoryId = selectedCategory?.id,
                        onSelect = { selectedCategory = it }
                    )
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Quelltext einfügen oder eingeben *") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), tags.trim(), selectedCategory, body.trim()) },
                enabled = title.isNotBlank() && body.trim().length >= 20
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun EditSourceMetaDialog(
    source: SourceDocumentEntity,
    categories: List<LearningCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, tags: String, category: LearningCategoryEntity?) -> Unit
) {
    var title by remember { mutableStateOf(source.title) }
    var tags by remember { mutableStateOf(source.tags) }
    var selectedCategory by remember {
        mutableStateOf(categories.firstOrNull { it.id == source.categoryId })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quelle bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Schlagworte") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    Text("Kategorie / Kompetenzbereich:", style = MaterialTheme.typography.labelLarge)
                    CategorySelectorChips(
                        categories = categories,
                        selectedCategoryId = selectedCategory?.id,
                        onSelect = { selectedCategory = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, tags, selectedCategory) },
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

@Composable
private fun SourceChunksDialog(
    source: SourceDocumentEntity,
    repository: PflegeRepository,
    onDismiss: () -> Unit
) {
    val chunks by repository.observeSourceChunks(source.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Indexierte Abschnitte: ${source.title}") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chunks, key = { it.id }) { chunk ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val pageTxt = chunk.pageNumber?.let { "Seite/Scan $it · " } ?: ""
                            Text(
                                text = "$pageTxt${chunk.sectionHeading}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = chunk.content,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}

private fun createTempCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "ocr_captures").apply { mkdirs() }
    val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
