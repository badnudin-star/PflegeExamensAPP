package de.pflegeexamen.app.ui.screens.preparation

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import de.pflegeexamen.app.data.local.StudyNoteEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.domain.model.SourceCitation
import de.pflegeexamen.app.domain.model.StudyNoteSection
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CategoryFilterBar
import de.pflegeexamen.app.ui.components.CitationBadgeChip
import de.pflegeexamen.app.ui.components.CitationDetailDialog
import de.pflegeexamen.app.ui.components.EmptyStateCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyNotesTab(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by repository.studyNotes.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<MasteryStatus?>(null) }
    var categoryFilter by remember { mutableStateOf<Long?>(null) }
    var openedNoteId by remember { mutableStateOf<Long?>(null) }
    var activeCitation by remember { mutableStateOf<SourceCitation?>(null) }

    val filtered = remember(notes, query, statusFilter, categoryFilter) {
        notes.filter { note ->
            val matchesQuery = query.isBlank() ||
                note.title.contains(query, ignoreCase = true) ||
                note.summaryMarkdown.contains(query, ignoreCase = true)
            val matchesStatus = statusFilter == null || note.masteryStatus == statusFilter
            val matchesCat = categoryFilter == null || note.categoryId == categoryFilter
            matchesQuery && matchesStatus && matchesCat
        }
    }

    val openedNote = remember(notes, openedNoteId) {
        notes.firstOrNull { it.id == openedNoteId }
    }

    if (openedNote != null) {
        StudyNoteDetailView(
            note = openedNote,
            repository = repository,
            onBack = { openedNoteId = null },
            onCitationClick = { activeCitation = it },
            onShare = {
                val shareText = buildString {
                    append(openedNote.summaryMarkdown)
                    if (openedNote.personalAnnotation.isNotBlank()) {
                        append("\n\n---\nEigene Notiz:\n${openedNote.personalAnnotation}")
                    }
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, openedNote.title)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(intent, "Lernzettel teilen"))
            },
            onSaveAnnotation = { text ->
                scope.launch {
                    repository.updateStudyNoteAnnotation(openedNote.id, text)
                    snackbarHostState.showSnackbar("Eigene Ergänzung gespeichert.")
                }
            },
            onChangeStatus = { status ->
                scope.launch {
                    val gainedXp = openedNote.masteryStatus != MasteryStatus.MASTERED && status == MasteryStatus.MASTERED
                    repository.updateStudyNoteStatus(openedNote.id, openedNote.masteryStatus, status)
                    if (gainedXp) {
                        snackbarHostState.showSnackbar("Thema als „Sicher beherrscht“ markiert (+25 XP)!")
                    }
                }
            }
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("Lernzettel durchsuchen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = statusFilter == null,
                            onClick = { statusFilter = null },
                            label = { Text("Alle (${notes.size})") }
                        )
                        MasteryStatus.entries.forEach { status ->
                            FilterChip(
                                selected = statusFilter == status,
                                onClick = {
                                    statusFilter = if (statusFilter == status) null else status
                                },
                                label = { Text(status.displayLabel) }
                            )
                        }
                    }
                    if (notes.isNotEmpty() && categories.isNotEmpty()) {
                        CategoryFilterBar(
                            categories = categories,
                            selectedCategoryId = categoryFilter,
                            onSelectCategory = { categoryFilter = it }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Description,
                        title = if (notes.isEmpty()) "Noch keine Lernzettel generiert" else "Keine passenden Lernzettel",
                        description = if (notes.isEmpty()) {
                            "Lege eigene Quellen und mindestens ein Thema in der Vorbereitungsliste an und tippe auf „Lernzettel & Karten erzeugen“."
                        } else {
                            "Passe Suchbegriff, Status- oder Kategoriefilter an."
                        }
                    )
                }
            } else {
                items(filtered, key = { it.id }) { note ->
                    val catColor = categories.firstOrNull { it.id == note.categoryId }?.colorHex
                    ElevatedCard(
                        onClick = { openedNoteId = note.id },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = note.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            repository.deleteStudyNote(note)
                                            snackbarHostState.showSnackbar("Lernzettel gelöscht.")
                                        }
                                    }
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (note.categoryLabel.isNotBlank()) {
                                    CategoryBadge(label = note.categoryLabel, colorHex = catColor)
                                }
                                AssistChip(
                                    onClick = { openedNoteId = note.id },
                                    label = { Text(note.masteryStatus.displayLabel, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                            Text(
                                text = "Quellen: ${note.sourceSummary}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
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
private fun StudyNoteDetailView(
    note: StudyNoteEntity,
    repository: PflegeRepository,
    onBack: () -> Unit,
    onCitationClick: (SourceCitation) -> Unit,
    onShare: () -> Unit,
    onSaveAnnotation: (String) -> Unit,
    onChangeStatus: (MasteryStatus) -> Unit
) {
    val sections = remember(note.sectionsJson) {
        runCatching {
            repository.json.decodeFromString<List<StudyNoteSection>>(note.sectionsJson)
        }.getOrDefault(emptyList())
    }
    var personalNote by remember(note.id, note.personalAnnotation) {
        mutableStateOf(note.personalAnnotation)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← Zurück zur Liste")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = "Lernzettel teilen")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note.title, style = MaterialTheme.typography.headlineSmall)
                if (note.categoryLabel.isNotBlank()) {
                    CategoryBadge(label = note.categoryLabel)
                }
                Text(
                    text = "Quellenbasis: ${note.sourceSummary}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MasteryStatus.entries.forEach { status ->
                        FilterChip(
                            selected = note.masteryStatus == status,
                            onClick = { onChangeStatus(status) },
                            label = { Text(status.displayLabel) }
                        )
                    }
                }
            }
        }

        items(sections) { section ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = section.parameterTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    section.bullets.forEach { bullet ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "• ${bullet.statement}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            CitationBadgeChip(
                                citation = bullet.citation,
                                onClick = { onCitationClick(bullet.citation) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Eigene Lernnotiz / Prüfungs-Merkhilfe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = personalNote,
                        onValueChange = { personalNote = it },
                        label = { Text("Eigene Ergänzungen festhalten") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { onSaveAnnotation(personalNote) }) {
                            Text("Notiz speichern")
                        }
                    }
                }
            }
        }
    }
}
