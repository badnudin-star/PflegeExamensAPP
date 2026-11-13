package de.pflegeexamen.app.ui.screens.preparation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.FlashcardEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.ui.components.CategoryBadge
import de.pflegeexamen.app.ui.components.CategoryFilterBar
import de.pflegeexamen.app.ui.components.EmptyStateCard
import de.pflegeexamen.app.ui.theme.ErrorRed
import de.pflegeexamen.app.ui.theme.SuccessGreen
import de.pflegeexamen.app.ui.theme.WarningOrange
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlashcardsTab(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val allCards by repository.flashcards.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var topicFilter by remember { mutableStateOf<String?>(null) }
    var categoryFilter by remember { mutableStateOf<Long?>(null) }
    var onlyDue by remember { mutableStateOf(true) }
    var showManageList by remember { mutableStateOf(false) }

    val topics = remember(allCards, categoryFilter) {
        allCards
            .filter { categoryFilter == null || it.categoryId == categoryFilter }
            .map { it.topicTitle }
            .distinct()
            .sorted()
    }

    val now = remember(allCards, onlyDue, topicFilter, categoryFilter) { System.currentTimeMillis() }
    val activeDeck = remember(allCards, topicFilter, categoryFilter, onlyDue, now) {
        allCards.filter { card ->
            val catOk = categoryFilter == null || card.categoryId == categoryFilter
            val topicOk = topicFilter == null || card.topicTitle == topicFilter
            val dueOk = !onlyDue || card.nextReviewAt <= now
            catOk && topicOk && dueOk
        }
    }

    var isFlipped by remember { mutableStateOf(false) }
    var showExcerpt by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = !showManageList && onlyDue,
                    onClick = {
                        showManageList = false
                        onlyDue = true
                        isFlipped = false
                    },
                    label = { Text("Nur fällige lernen") }
                )
                FilterChip(
                    selected = !showManageList && !onlyDue,
                    onClick = {
                        showManageList = false
                        onlyDue = false
                        isFlipped = false
                    },
                    label = { Text("Alle üben (${allCards.size})") }
                )
                FilterChip(
                    selected = showManageList,
                    onClick = { showManageList = !showManageList },
                    label = { Text("Karten verwalten") }
                )
            }
        }

        if (allCards.isNotEmpty() && categories.isNotEmpty()) {
            item {
                CategoryFilterBar(
                    categories = categories,
                    selectedCategoryId = categoryFilter,
                    onSelectCategory = {
                        categoryFilter = it
                        topicFilter = null
                        isFlipped = false
                    }
                )
            }
        }

        if (topics.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = topicFilter == null,
                        onClick = {
                            topicFilter = null
                            isFlipped = false
                        },
                        label = { Text("Alle Themen") }
                    )
                    topics.forEach { t ->
                        FilterChip(
                            selected = topicFilter == t,
                            onClick = {
                                topicFilter = if (topicFilter == t) null else t
                                isFlipped = false
                            },
                            label = { Text(t) }
                        )
                    }
                }
            }
        }

        if (allCards.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.Style,
                    title = "Noch keine Karteikarten vorhanden",
                    description = "Lege ein Thema in der Vorbereitungsliste an und generiere daraus automatisiert Karteikarten aus deinen eigenen Quellen."
                )
            }
        } else if (showManageList) {
            val listCards = allCards.filter {
                (categoryFilter == null || it.categoryId == categoryFilter) &&
                    (topicFilter == null || it.topicTitle == topicFilter)
            }
            items(listCards, key = { it.id }) { card ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${card.topicTitle} · ${card.cardType.displayLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        repository.deleteFlashcard(card)
                                        snackbarHostState.showSnackbar("Karte gelöscht.")
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                            }
                        }
                        if (card.categoryLabel.isNotBlank()) {
                            CategoryBadge(label = card.categoryLabel)
                        }
                        Text(card.frontText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(card.backText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (activeDeck.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Alle fälligen Karten für jetzt geschafft!",
                    description = "Für deine aktuelle Auswahl sind aktuell keine Karten zur Wiederholung fällig. Du kannst oben auf „Alle üben“ umschalten.",
                    actionLabel = "Alle Karten trotzdem üben",
                    onAction = { onlyDue = false }
                )
            }
        } else {
            val currentCard = activeDeck.first()
            item(key = currentCard.id) {
                FlashcardStudyCard(
                    card = currentCard,
                    remainingCount = activeDeck.size,
                    isFlipped = isFlipped,
                    showExcerpt = showExcerpt,
                    onToggleFlip = { isFlipped = !isFlipped },
                    onToggleExcerpt = { showExcerpt = !showExcerpt },
                    onRate = { quality ->
                        scope.launch {
                            repository.reviewFlashcard(currentCard, quality)
                            isFlipped = false
                            showExcerpt = false
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashcardStudyCard(
    card: FlashcardEntity,
    remainingCount: Int,
    isFlipped: Boolean,
    showExcerpt: Boolean,
    onToggleFlip: () -> Unit,
    onToggleExcerpt: () -> Unit,
    onRate: (Int) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (card.categoryLabel.isNotBlank()) {
                        CategoryBadge(label = card.categoryLabel)
                    }
                    AssistChip(onClick = {}, label = { Text(card.cardType.displayLabel) })
                    if (card.parameterTag.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(card.parameterTag) })
                    }
                }
                Text(
                    text = "Noch $remainingCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = card.topicTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (!isFlipped) "Frage / Impuls" else "Antwort (aus eigener Quelle)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (!isFlipped) card.frontText else card.backText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (!isFlipped) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            if (!isFlipped) {
                Button(
                    onClick = onToggleFlip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Antwort aufdecken")
                }
            } else {
                OutlinedButton(
                    onClick = onToggleExcerpt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showExcerpt) "Original-Beleg ausblenden" else "Original-Beleg (${card.citationLabel}) anzeigen")
                }

                if (showExcerpt) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = card.citationLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "„${card.sourceExcerpt}“",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text(
                    text = "Wie sicher saß die Antwort? (SM-2 Intervall · +5 bis +10 XP)",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onRate(1) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = ErrorRed.copy(alpha = 0.14f),
                            contentColor = ErrorRed
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text("Nochmal", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { onRate(3) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = WarningOrange.copy(alpha = 0.16f),
                            contentColor = WarningOrange
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text("Schwer", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { onRate(4) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text("Gut", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { onRate(5) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SuccessGreen.copy(alpha = 0.16f),
                            contentColor = SuccessGreen
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text("Leicht", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
