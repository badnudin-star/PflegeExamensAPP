package de.pflegeexamen.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.domain.model.AiProviderMode
import de.pflegeexamen.app.domain.model.AiSettings
import de.pflegeexamen.app.domain.model.SourceCitation

@Composable
fun OfflineSourceBanner(
    aiSettings: AiSettings = AiSettings(),
    text: String? = null,
    onClickSettings: (() -> Unit)? = null
) {
    val isRemoteReady = aiSettings.isReadyForRemoteCall()
    val bannerText = text ?: when {
        aiSettings.isOnDevice() -> "Smartphone-KI: Verfügbarkeit in den Einstellungen prüfen. KI-Ergebnisse fachlich überprüfen."
        aiSettings.mode == AiProviderMode.OFFLINE_ONLY ->
            "100 % Offline & streng quellenbasiert (0 €): Alle Ausgaben stammen ausschließlich aus deinen eigenen Quellen."
        isRemoteReady ->
            "Gratis-KI aktiv (${aiSettings.mode.displayLabel}) + streng quellenbasiert: Antworten werden ausschließlich aus deinen Textstellen gebildet (Fallback: Offline)."
        else ->
            "${aiSettings.mode.displayLabel} gewählt, noch unvollständig konfiguriert – aktuell läuft automatisch die 100-%-Offline-Engine."
    }

    val containerColor = if (isRemoteReady) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    }

    Surface(
        onClick = { onClickSettings?.invoke() },
        enabled = onClickSettings != null,
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isRemoteReady) Icons.Outlined.AutoAwesome else Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = bannerText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryFilterBar(
    categories: List<LearningCategoryEntity>,
    selectedCategoryId: Long?,
    onSelectCategory: (Long?) -> Unit
) {
    if (categories.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = selectedCategoryId == null,
            onClick = { onSelectCategory(null) },
            label = { Text("Alle Bereiche", style = MaterialTheme.typography.labelMedium) }
        )
        categories.forEach { cat ->
            FilterChip(
                selected = selectedCategoryId == cat.id,
                onClick = {
                    onSelectCategory(if (selectedCategoryId == cat.id) null else cat.id)
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(cat.colorHex))
                    )
                },
                label = { Text(cat.shortCode.ifBlank { cat.name }, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelectorChips(
    categories: List<LearningCategoryEntity>,
    selectedCategoryId: Long?,
    onSelect: (LearningCategoryEntity?) -> Unit
) {
    if (categories.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = selectedCategoryId == null,
            onClick = { onSelect(null) },
            label = { Text("Ohne Kategorie", style = MaterialTheme.typography.labelMedium) }
        )
        categories.forEach { cat ->
            FilterChip(
                selected = selectedCategoryId == cat.id,
                onClick = { onSelect(cat) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(cat.colorHex))
                    )
                },
                label = { Text("${cat.shortCode}: ${cat.name}", style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
fun CategoryBadge(label: String, colorHex: Long? = null) {
    if (label.isBlank()) return
    Surface(
        color = Color(colorHex ?: 0xFF0E6B6EL).copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(colorHex ?: 0xFF0E6B6EL))
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun CitationBadgeChip(
    citation: SourceCitation,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = citation.shortLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun CitationDetailDialog(
    citation: SourceCitation,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quellenbeleg") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = citation.sourceTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val meta = listOfNotNull(
                    citation.pageLabel.takeIf { it.isNotBlank() },
                    citation.sectionHeading.takeIf { it.isNotBlank() && it != "Allgemein" }?.let { "Abschnitt: $it" }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = "„${citation.excerpt}“",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}
