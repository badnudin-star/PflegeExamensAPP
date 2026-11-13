package de.pflegeexamen.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.ui.components.OfflineSourceBanner

@Composable
fun HomeScreen(
    repository: PflegeRepository,
    onNavigateToPreparation: () -> Unit,
    onNavigateToFlashcards: () -> Unit,
    onNavigateToCaseStudies: () -> Unit,
    onNavigateToRanking: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val aiSettings by repository.aiSettings.collectAsStateWithLifecycle()
    val topics by repository.preparationItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by repository.studyNotes.collectAsStateWithLifecycle(initialValue = emptyList())
    val dueCount by repository.observeDueFlashcardCount().collectAsStateWithLifecycle(initialValue = 0)
    val cases by repository.caseStudies.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Heute lernen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Dein nächster Schritt zum Pflegeexamen", style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Einstellungen")
                }
            }
        }
        item { OfflineSourceBanner(aiSettings = aiSettings, onClickSettings = onNavigateToSettings) }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Karteikarten wiederholen", style = MaterialTheme.typography.titleLarge)
                    Text(if (dueCount > 0) "$dueCount Karten sind heute fällig." else "Momentan sind keine Karten fällig.")
                    Button(onClick = onNavigateToFlashcards) { Text("Karten üben") }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Themen & Lernzettel", style = MaterialTheme.typography.titleLarge)
                    Text("${topics.size} Themen · ${notes.size} Lernzettel")
                    Text("Wähle ein Thema und erstelle Lernmaterial für deine Prüfung.")
                    OutlinedButton(onClick = onNavigateToPreparation) { Text("Themen öffnen") }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Falltraining", style = MaterialTheme.typography.titleLarge)
                    Text("${cases.size} gespeicherte Fallbeispiele")
                    Text("Trainiere Prüfungsfragen und vergleiche deine Antwort mit der Musterlösung.")
                    OutlinedButton(onClick = onNavigateToCaseStudies) { Text("Falltraining öffnen") }
                }
            }
        }
        item {
            OutlinedButton(onClick = onNavigateToRanking) { Text("Lernfortschritt & Ranking") }
        }
        item {
            Text(
                "KI-generierte Inhalte können Fehler enthalten. Überprüfe sie anhand verlässlicher Fachquellen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
