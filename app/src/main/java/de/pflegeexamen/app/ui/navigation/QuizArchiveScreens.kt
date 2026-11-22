package de.pflegeexamen.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.pflegeexamen.app.data.progress.QuizProgressStore
import de.pflegeexamen.app.data.quiz.ArchiveDayResult
import de.pflegeexamen.app.data.quiz.ArchivePageResult
import de.pflegeexamen.app.data.quiz.QuizArchiveClient
import de.pflegeexamen.app.domain.content.LearningCatalog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuizArchivePicker(client: QuizArchiveClient, activeDates: Set<String>, open: (String) -> Unit, back: () -> Unit) {
    var local by remember { mutableStateOf<List<String>?>(null) }
    var localLoaded by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(emptyList<String>()) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var loadedOnline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client) { local = client.cachedDates(); localLoaded = true }
    fun loadPage(before: String?) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            when (val result = client.page(before)) {
                is ArchivePageResult.Ready -> {
                    online = if (before == null) result.page.days.map { it.date } else (online + result.page.days.map { it.date }).distinct()
                    nextBefore = result.page.nextBefore
                    loadedOnline = true
                }
                ArchivePageResult.NotConfigured -> error = "Das Online-Archiv ist nicht eingerichtet."
                ArchivePageResult.Unavailable -> error = "Das Online-Archiv ist derzeit nicht verfügbar. Bereits geladene Quiztage bleiben auf diesem Gerät."
            }
            busy = false
        }
    }
    val dates = remember(local, online, activeDates) { ((local ?: emptyList()) + online).filterNot { it in activeDates }.distinct().sortedDescending() }
    Scaffold(topBar = { TopAppBar(title = { Text("Quizarchiv") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("quizArchiveList"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Frühere Quiztage", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Geöffnete Archivtage werden auf diesem Gerät gespeichert. Für noch nicht geladene Tage ist eine Verbindung nötig. Aktuelle Quiztage stehen in der Quizübersicht. Das Archiv ist eine lokale Übung und keine Ranglistenwertung.") }
            item { OutlinedButton(onClick = { loadPage(null) }, enabled = !busy) { Text(if (busy) "Archiv wird geladen …" else "Archiv online laden") } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (localLoaded && local == null) item { Text("Der lokale Archivspeicher ist nicht lesbar.", color = MaterialTheme.colorScheme.error) }
            else if (localLoaded && dates.isEmpty()) item { Text(if (loadedOnline) "Keine archivierten Quiztage gefunden." else "Noch keine Archivtage auf diesem Gerät. Lade bei Bedarf das Online-Archiv.") }
            items(dates, key = { it }) { date ->
                ElevatedCard(onClick = { open(date) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(date, style = MaterialTheme.typography.titleMedium)
                        Text(if (date in (local ?: emptyList())) "Auf diesem Gerät gespeichert" else "Online verfügbar · beim Öffnen herunterladen", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (nextBefore != null) item { OutlinedButton(onClick = { loadPage(nextBefore) }, enabled = !busy) { Text("Ältere Quiztage laden") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchivedQuizScreen(date: String, client: QuizArchiveClient, catalog: LearningCatalog, progress: QuizProgressStore,
    openNote: (Int, String) -> Unit, back: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val active = catalog.quizzes.any { it.published == date }
    val result by produceState<ArchiveDayResult?>(null, client, date, attempt, active) {
        value = null
        if (!active) value = client.day(date)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Archiv · $date") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zum Archiv") }
    }) }) { padding ->
        if (active) {
            Column(Modifier.padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dieser Quiztag ist im aktuellen Katalog verfügbar. Bitte öffne ihn in der Quizübersicht.")
                OutlinedButton(onClick = back) { Text("Zurück zum Archiv") }
            }
        } else when (val value = result) {
            null -> Text("Quiztag wird geladen …", Modifier.padding(padding).padding(18.dp))
            is ArchiveDayResult.Ready -> QuizScreen(catalog.copy(quizzes = value.day.quizzes), progress, openNote = openNote, modifier = Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (value) {
                    ArchiveDayResult.NotConfigured -> "Das Online-Archiv ist nicht eingerichtet."
                    ArchiveDayResult.StorageError -> "Der Quiztag konnte nicht sicher vom Gerät gelesen oder gespeichert werden. Vorhandene Daten wurden nicht überschrieben."
                    else -> "Der Quiztag ist nicht verfügbar. Bitte später mit Verbindung erneut versuchen."
                })
                OutlinedButton(onClick = { attempt++ }) { Text("Erneut versuchen") }
            }
        }
    }
}
