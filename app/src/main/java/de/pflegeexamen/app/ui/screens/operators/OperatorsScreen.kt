package de.pflegeexamen.app.ui.screens.operators

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import de.pflegeexamen.app.data.local.OperatorItemEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.ui.components.EmptyStateCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperatorsScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val operators by repository.operators.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTaxFilter by remember { mutableStateOf("Alle") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingOperator by remember { mutableStateOf<OperatorItemEntity?>(null) }

    val filteredOperators = remember(operators, selectedTaxFilter) {
        when (selectedTaxFilter) {
            "WV" -> operators.filter { it.requirementLevel.contains("WV", ignoreCase = true) }
            "ASB" -> operators.filter { it.requirementLevel.contains("ASB", ignoreCase = true) }
            else -> operators
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
                    val count = repository.importOperatorsJson(raw)
                    snackbarHostState.showSnackbar("$count Operatoren importiert.")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Import fehlgeschlagen: ${e.message}")
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
                    val json = repository.exportOperatorsJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    snackbarHostState.showSnackbar("Operatorenliste als JSON exportiert.")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Export fehlgeschlagen: ${e.message}")
                }
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
                        text = "Operatorenliste (Landes-AG / Jann Wegner, Dez. 2025)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Teil A (WV – Wissen & Verstehen) und Teil B (ASB – Analyse, Synthese, Bewertung) mit Beispielfrage, Erwartungshorizont, Kommentar (K) und Bepunktung – vollständig editierbar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Operator anlegen")
                        }
                        OutlinedButton(onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                            Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Import")
                        }
                        if (operators.isNotEmpty()) {
                            OutlinedButton(onClick = { exportJsonLauncher.launch("operatorenliste_dez2025.json") }) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Export")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val added = repository.restoreMissingDefaultOperators()
                                    snackbarHostState.showSnackbar(
                                        if (added > 0) "$added Landes-AG-Operator(en) ergänzt/aktualisiert."
                                        else "Alle 17 Landes-AG-Operatoren sind vollständig geladen."
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Landes-AG ergänzen")
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Alle" to "Alle (${operators.size})",
                            "WV" to "Teil A · WV (${operators.count { it.requirementLevel.contains("WV", true) }})",
                            "ASB" to "Teil B · ASB (${operators.count { it.requirementLevel.contains("ASB", true) }})"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = selectedTaxFilter == key,
                                onClick = { selectedTaxFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        if (filteredOperators.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.Quiz,
                    title = "Keine Operatoren in dieser Ansicht",
                    description = "Lade die 17 offiziellen Operatoren der Landes-AG (Dezember 2025) oder lege eigene Operatoren an.",
                    actionLabel = "Landes-AG-Operatoren laden",
                    onAction = {
                        scope.launch {
                            val count = repository.restoreMissingDefaultOperators()
                            snackbarHostState.showSnackbar("$count Operatoren geladen.")
                        }
                    }
                )
            }
        } else {
            items(filteredOperators, key = { it.id }) { op ->
                OperatorDetailCard(
                    op = op,
                    onEdit = { editingOperator = op },
                    onDelete = {
                        scope.launch {
                            repository.deleteOperator(op)
                            snackbarHostState.showSnackbar("Operator „${op.name}“ gelöscht.")
                        }
                    }
                )
            }
        }
    }

    if (showCreateDialog || editingOperator != null) {
        OperatorEditorDialog(
            initial = editingOperator,
            onDismiss = {
                showCreateDialog = false
                editingOperator = null
            },
            onSave = { entity ->
                scope.launch {
                    repository.saveOperator(entity)
                    showCreateDialog = false
                    editingOperator = null
                    snackbarHostState.showSnackbar("Operator „${entity.name}“ gespeichert.")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperatorDetailCard(
    op: OperatorItemEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(op.id) { mutableStateOf(false) }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = op.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val meta = listOfNotNull(
                        op.requirementLevel.takeIf { it.isNotBlank() },
                        "${op.defaultPoints} Punkte (Richtwert)"
                    ).joinToString(" · ")
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = "Details"
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(op.caseRelationRule.displayLabel, style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(op.structureType.displayLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }

            if (op.description.isNotBlank()) {
                Text(
                    text = op.description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (op.gradingRubric.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = "Bepunktung: ${op.gradingRubric}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (expanded) {
                if (op.exampleQuestion.isNotBlank()) {
                    Text(
                        text = "Beispielfrage aus Operatorenliste:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(op.exampleQuestion, style = MaterialTheme.typography.bodyMedium)
                }
                if (op.expectationHorizon.isNotBlank()) {
                    Text(
                        text = "Erwartungshorizont:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(op.expectationHorizon, style = MaterialTheme.typography.bodyMedium)
                }
                if (op.commentNotes.isNotBlank()) {
                    Text(
                        text = "Kommentar (K):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(op.commentNotes, style = MaterialTheme.typography.bodyMedium)
                }
                if (op.questionPattern.isNotBlank()) {
                    Text(
                        text = "Fragemuster für Fallbeispiele: ${op.questionPattern}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (op.exampleQuestion.isNotBlank() || op.expectationHorizon.isNotBlank() || op.commentNotes.isNotBlank()) {
                TextButton(onClick = { expanded = true }) {
                    Text("Beispielfrage, Erwartungshorizont & Kommentar (K) anzeigen")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperatorEditorDialog(
    initial: OperatorItemEntity?,
    onDismiss: () -> Unit,
    onSave: (OperatorItemEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var level by remember { mutableStateOf(initial?.requirementLevel.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var exampleQuestion by remember { mutableStateOf(initial?.exampleQuestion.orEmpty()) }
    var expectationHorizon by remember { mutableStateOf(initial?.expectationHorizon.orEmpty()) }
    var commentNotes by remember { mutableStateOf(initial?.commentNotes.orEmpty()) }
    var gradingRubric by remember { mutableStateOf(initial?.gradingRubric.orEmpty()) }
    var caseRelationRule by remember {
        mutableStateOf(initial?.caseRelationRule ?: CaseRelationRule.OPTIONAL_CASE_RELATION)
    }
    var pattern by remember { mutableStateOf(initial?.questionPattern.orEmpty()) }
    var structureType by remember {
        mutableStateOf(initial?.structureType ?: OperatorStructureType.CAUSAL_REASONING)
    }
    var defaultPoints by remember { mutableStateOf((initial?.defaultPoints ?: 4).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Operator anlegen" else "Operator bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Operator / Arbeitsauftrag *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = level,
                    onValueChange = { level = it },
                    label = { Text("Taxonomie / Stufe (z. B. Taxonomie WV oder Taxonomie ASB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Fallbezugs-Regel:", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CaseRelationRule.entries.forEach { rule ->
                        FilterChip(
                            selected = caseRelationRule == rule,
                            onClick = { caseRelationRule = rule },
                            label = { Text(rule.displayLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Kurzbeschreibung") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exampleQuestion,
                    onValueChange = { exampleQuestion = it },
                    label = { Text("Frage aus der Operatorenliste (Beispiel)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = expectationHorizon,
                    onValueChange = { expectationHorizon = it },
                    label = { Text("Erwartungshorizont (Muster/Aufbau)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = commentNotes,
                    onValueChange = { commentNotes = it },
                    label = { Text("Kommentar (K1, K2, K3)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gradingRubric,
                    onValueChange = { gradingRubric = it },
                    label = { Text("Bepunktung") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Passende Textstruktur in den Quellen:", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OperatorStructureType.entries.forEach { st ->
                        FilterChip(
                            selected = structureType == st,
                            onClick = { structureType = st },
                            label = { Text(st.displayLabel, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Fragevorlage für Generator (optional)") },
                    placeholder = { Text("z. B. {operator} anhand des Falls zu {aspekt}.") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Punktrichtwert: ${defaultPoints.roundToInt()} Punkte",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = defaultPoints,
                    onValueChange = { defaultPoints = it },
                    valueRange = 1f..20f,
                    steps = 18
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: OperatorItemEntity(name = name.trim())).copy(
                            name = name.trim(),
                            requirementLevel = level.trim(),
                            description = description.trim(),
                            exampleQuestion = exampleQuestion.trim(),
                            expectationHorizon = expectationHorizon.trim(),
                            commentNotes = commentNotes.trim(),
                            gradingRubric = gradingRubric.trim(),
                            caseRelationRule = caseRelationRule,
                            questionPattern = pattern.trim(),
                            structureType = structureType,
                            defaultPoints = defaultPoints.roundToInt()
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
