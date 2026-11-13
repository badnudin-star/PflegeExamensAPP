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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.CaseStudyWithQuestions
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.data.local.OperatorItemEntity
import de.pflegeexamen.app.data.local.PreparationItemEntity
import de.pflegeexamen.app.data.local.SourceDocumentEntity
import de.pflegeexamen.app.data.repository.CaseGenerationReport
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CategoryFilterBar
import de.pflegeexamen.app.ui.components.CategorySelectorChips
import de.pflegeexamen.app.ui.components.EmptyStateCard
import kotlinx.coroutines.launch

@Composable
fun CaseStudiesScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    onOpenCaseStudy: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val caseStudies by repository.caseStudies.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by repository.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val operators by repository.operators.collectAsStateWithLifecycle(initialValue = emptyList())
    val prepItems by repository.preparationItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedCategoryFilter by remember { mutableStateOf<Long?>(null) }
    var showGeneratorDialog by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    val filteredCaseStudies = remember(caseStudies, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) caseStudies
        else caseStudies.filter { it.caseStudy.categoryId == selectedCategoryFilter }
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
                        text = "Falltraining und Prüfungsfragen",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Übe mit Fallbeispielen und Fragen. Die Musterlösung erscheint nach deiner Antwort. KI-Inhalte bitte fachlich überprüfen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showGeneratorDialog = true },
                            enabled = operators.isNotEmpty() && !isGenerating
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Fallbeispiel generieren")
                        }

                    }

                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Erstelle Fallbeispiel und Fragen...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (caseStudies.isNotEmpty() && categories.isNotEmpty()) {
            item {
                CategoryFilterBar(
                    categories = categories,
                    selectedCategoryId = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it }
                )
            }
        }

        if (filteredCaseStudies.isEmpty()) {
            item {
                val desc = when {
                    operators.isEmpty() -> "Importiere ein Lernpaket mit Aufgaben in den Einstellungen."
                    caseStudies.isNotEmpty() -> "Keine Fallbeispiele in dieser Kategorie. Wähle „Alle Bereiche“."
                    else -> "Starte dein erstes Fallbeispiel über „Fallbeispiel generieren“."
                }
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Outlined.Assignment,
                    title = if (caseStudies.isEmpty()) "Noch keine Fallbeispiele erstellt" else "Keine passenden Fallbeispiele",
                    description = desc,
                    actionLabel = if (operators.isNotEmpty() && caseStudies.isEmpty()) "Jetzt generieren" else null,
                    onAction = if (operators.isNotEmpty() && caseStudies.isEmpty()) {
                        { showGeneratorDialog = true }
                    } else null
                )
            }
        } else {
            items(filteredCaseStudies, key = { it.caseStudy.id }) { item ->
                val catColor = categories.firstOrNull { it.id == item.caseStudy.categoryId }?.colorHex
                CaseStudyListCard(
                    item = item,
                    categoryColorHex = catColor,
                    onOpen = { onOpenCaseStudy(item.caseStudy.id) },
                    onDelete = {
                        scope.launch {
                            repository.deleteCaseStudy(item.caseStudy)
                            snackbarHostState.showSnackbar("Fallbeispiel gelöscht.")
                        }
                    }
                )
            }
        }
    }

    if (showGeneratorDialog) {
        CaseStudyGeneratorDialog(
            prepItems = prepItems,
            sources = sources,
            operators = operators,
            categories = categories,
            onDismiss = { showGeneratorDialog = false },
            onGenerate = { topicTitle, keywords, prepId, categoryId, categoryLabel, selectedSourceIds, selectedOperators ->
                showGeneratorDialog = false
                scope.launch {
                    isGenerating = true
                    val report = repository.generateCaseStudy(
                        topicTitle = topicTitle,
                        keywords = keywords,
                        prepItemId = prepId,
                        categoryId = categoryId,
                        categoryLabel = categoryLabel,
                        selectedSourceIds = selectedSourceIds,
                        selectedOperators = selectedOperators,
                        detailDepth = DetailDepth.EXAM_DEEP
                    )
                    isGenerating = false
                    when (report) {
                        is CaseGenerationReport.Success -> {
                            val skippedNote = if (report.skippedOperatorsInfo.isNotEmpty()) {
                                " (${report.skippedOperatorsInfo.size} Operator(en) mangels Beleg übersprungen)"
                            } else ""
                            val fb = report.fallbackNote?.let { " · $it" } ?: ""
                            snackbarHostState.showSnackbar(
                                "Fallbeispiel [${report.engineUsedLabel}] mit ${report.questionCount} Frage(n) erstellt$skippedNote$fb"
                            )
                            onOpenCaseStudy(report.caseStudyId)
                        }
                        is CaseGenerationReport.NoEvidenceFound -> {
                            snackbarHostState.showSnackbar(report.message)
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseStudyListCard(
    item: CaseStudyWithQuestions,
    categoryColorHex: Long?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val total = item.questions.size
    val confirmed = item.questions.count { it.isAnswerConfirmed }
    val progress = if (total > 0) confirmed.toFloat() / total.toFloat() else 0f

    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
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
                    text = item.caseStudy.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.caseStudy.categoryLabel.isNotBlank()) {
                    CategoryBadge(label = item.caseStudy.categoryLabel, colorHex = categoryColorHex)
                }
                AssistChip(
                    onClick = onOpen,
                    label = {
                        Text(
                            if (item.caseStudy.isExtractedOriginalCase) "Originalfall aus Quelle" else "Lernfall",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
                AssistChip(
                    onClick = onOpen,
                    label = { Text("$confirmed / $total bestätigt", style = MaterialTheme.typography.labelMedium) }
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Grundlage: ${item.caseStudy.sourceBasisLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseStudyGeneratorDialog(
    prepItems: List<PreparationItemEntity>,
    sources: List<SourceDocumentEntity>,
    operators: List<OperatorItemEntity>,
    categories: List<LearningCategoryEntity>,
    onDismiss: () -> Unit,
    onGenerate: (
        topicTitle: String,
        keywords: List<String>,
        prepId: Long?,
        categoryId: Long?,
        categoryLabel: String,
        selectedSourceIds: Set<Long>,
        selectedOperators: List<OperatorItemEntity>
    ) -> Unit
) {
    var selectedPrepId by remember { mutableStateOf<Long?>(null) }
    var selectedCategory by remember { mutableStateOf<LearningCategoryEntity?>(null) }
    var topicTitle by remember { mutableStateOf("") }
    var keywordsCsv by remember { mutableStateOf("") }

    val chosenSourceIds = remember { mutableStateListOf<Long>() }
    val chosenOperatorIds = remember {
        mutableStateListOf<Long>().apply {
            addAll(operators.take(4).map { it.id })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Fallbeispiel konfigurieren") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (prepItems.isNotEmpty()) {
                    var prepDayFilter by remember { mutableStateOf("Alle") }
                    Text("Thema aus der festen Themenliste wählen:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Alle", "Tag 1", "Tag 2", "Tag 3").forEach { d ->
                            FilterChip(
                                selected = prepDayFilter == d,
                                onClick = { prepDayFilter = d },
                                label = { Text(d, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    val visiblePreps = remember(prepItems, prepDayFilter) {
                        if (prepDayFilter == "Alle") prepItems
                        else prepItems.filter { it.examDayTag.equals(prepDayFilter, ignoreCase = true) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visiblePreps.forEach { prep ->
                            FilterChip(
                                selected = selectedPrepId == prep.id,
                                onClick = {
                                    if (selectedPrepId == prep.id) {
                                        selectedPrepId = null
                                    } else {
                                        selectedPrepId = prep.id
                                        topicTitle = prep.title
                                        keywordsCsv = prep.keywordsCsv
                                        selectedCategory = categories.firstOrNull { it.id == prep.categoryId }
                                    }
                                },
                                label = {
                                    val prefix = if (prep.examDayTag.isNotBlank()) "${prep.examDayTag}: " else ""
                                    Text("$prefix${prep.title}", style = MaterialTheme.typography.labelMedium)
                                }
                            )
                        }
                    }
                }



            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val kw = keywordsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val ops = operators.filter { it.id in chosenOperatorIds }
                    onGenerate(
                        topicTitle.trim(),
                        kw,
                        selectedPrepId,
                        selectedCategory?.id,
                        selectedCategory?.shortCode ?: selectedCategory?.name ?: "",
                        chosenSourceIds.toSet(),
                        ops
                    )
                },
                enabled = selectedPrepId != null && chosenOperatorIds.isNotEmpty()
            ) {
                Text("Generieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
