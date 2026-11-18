package de.pflegeexamen.app.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.pflegeexamen.app.data.content.PublishedContentStore
import de.pflegeexamen.app.data.progress.CaseProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCaseProgress
import de.pflegeexamen.app.domain.content.*
import de.pflegeexamen.app.domain.cases.CasePresentation
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.notes.NotePresentation

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("home", "Heute", Icons.Default.Home), Destination("topics", "Themen", Icons.Default.MenuBook),
    Destination("cards", "Karten", Icons.Default.Style), Destination("cases", "Fallbeispiele", Icons.Default.Assignment),
    Destination("quiz", "Quiz", Icons.Default.Quiz)
)

@Composable
fun LearningApp(store: PublishedContentStore) {
    val catalog by store.catalog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val caseProgress = remember(context.applicationContext) { CaseProgressStore(SharedPreferencesCaseProgress(context)) }
    val completedCases by caseProgress.completed.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showNavigation = destinations.any { it.route == route }
    Scaffold(bottomBar = {
        if (showNavigation) NavigationBar {
            destinations.forEach { destination ->
                NavigationBarItem(selected = destination.route == route, onClick = {
                    nav.navigate(destination.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { NewHome(catalog, { nav.navigate(it) }, { nav.navigate("settings") }) }
            composable("topics") { Topics(catalog) { index, depth -> nav.navigate("note/$index/$depth") } }
            composable("note/{index}/{depth}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val depth = entry.arguments?.getString("depth").orEmpty()
                val note = catalog.notes.firstOrNull { it.topic == topic }
                val content = when (depth) { "short" -> note?.short; "balanced" -> note?.balanced; else -> note?.detailed }
                val depthLabel = when (depth) { "short" -> "Kurz"; "balanced" -> "Ausgewogen"; else -> "Ausführlich" }
                NoteScreen(topic ?: "Lernzettel", depthLabel, content.orEmpty()) { nav.popBackStack() }
            }
            composable("cards") { CardPicker(catalog) { nav.navigate("card_session/$it") } }
            composable("card_session/{index}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val available = if (index == -1) catalog.cards else catalog.cards.filter { it.topic == topic }
                CardSession(available, index == -1) { nav.popBackStack() }
            }
            composable("cases") { CasePicker(catalog, completedCases, caseProgress::setCompleted) { nav.navigate("case/${Uri.encode(it)}") } }
            composable("case/{id}") { entry ->
                val case = catalog.cases.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (case != null) CaseDetail(case, case.id in completedCases, { caseProgress.setCompleted(case.id, it) }) { nav.popBackStack() } else FullTextScreen("Fallbeispiel", "Nicht verfügbar.", emptyList()) { nav.popBackStack() }
            }
            composable("quiz") { QuizScreen(catalog) }
            composable("ranking") { SimpleInfo("Quiz-Rangliste", "Das Cloudflare-Ranking ist noch nicht eingerichtet. Es werden keine alten Lernpunkte übertragen. Sobald die Quizwertung serverseitig bereitsteht, kann die Rangliste stündlich aktualisiert werden.") { nav.popBackStack() } }
            composable("settings") { AppSettings(store) { nav.popBackStack() } }
        }
    }
}

@Composable
private fun NewHome(catalog: LearningCatalog, navigate: (String) -> Unit, settings: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
            Text("Heute lernen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Dein nächster Schritt zum Pflegeexamen", style = MaterialTheme.typography.bodyMedium)
        }; IconButton(onClick = settings) { Icon(Icons.Default.Settings, "Einstellungen") } } }
        item { HomeCard("Themen & Lernzettel", "${DefaultCatalogs.defaultPreparations.size} Prüfungsthemen · ${catalog.notes.size} Themen mit Lernzetteln", "Themen öffnen") { navigate("topics") } }
        item { HomeCard("Karteikarten", "${catalog.cards.size} veröffentlichte Karten · nach Thema oder zufällig lernen", "Karten öffnen") { navigate("cards") } }
        item { HomeCard("Fallbeispiele", "${catalog.cases.size} veröffentlichte Fallbeispiele mit Fragen und Antworten", "Fälle ansehen") { navigate("cases") } }
        item { HomeCard("Multiple-Choice-Quiz", "${catalog.quizzes.size} veröffentlichte Fragen · vier Antworten pro Frage", "Quiz öffnen") { navigate("quiz") } }
        item { OutlinedButton(onClick = { navigate("ranking") }) { Text("Quiz-Rangliste") } }
        item { Text("Lernzettel aus bereitgestellten PDFs. Inhalte, Quellen und Aktualität bitte vor der praktischen Anwendung prüfen.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun HomeCard(title: String, body: String, action: String, click: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(body)
        OutlinedButton(onClick = click) { Text(action) }
    } }
}

@Composable
private fun Topics(catalog: LearningCatalog, open: (Int, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val topics = DefaultCatalogs.defaultPreparations
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Deine Themen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Ausführlich enthält ausführliche Lernzettel. Ausgewogen fasst die wichtigsten Inhalte zusammen. Kurz schneidet die Informationen nur an.", style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Alle Themen suchen") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }) }
        listOf("Tag 1", "Tag 2", "Tag 3").forEach { day ->
            val group = topics.withIndex().filter { it.value.examDayTag == day && (it.value.title.contains(query.trim(), true) || it.value.keywordsCsv.contains(query.trim(), true)) }
            if (group.isNotEmpty()) {
                item(key = "heading-$day") {
                    Surface(onClick = { collapsed[day] = !(collapsed[day] ?: false) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${group.size} Themen"); Icon(if (collapsed[day] == true) Icons.Default.ExpandMore else Icons.Default.ExpandLess, "Ein- oder ausklappen")
                        }
                    }
                }
                if (collapsed[day] != true) items(group, key = { "topic-${it.index}" }) { entry ->
                    val note = catalog.notes.firstOrNull { it.topic == entry.value.title }
                    TopicTile(entry.value.title, note != null) { open(entry.index, it) }
                }
            }
        }
        if (topics.none { it.title.contains(query.trim(), true) || it.keywordsCsv.contains(query.trim(), true) }) item { Text("Keine passenden Themen gefunden.") }
    }
}

private fun topicIcon(title: String): ImageVector = when {
    listOf("Herz", "Kreislauf", "Lunge", "Thrombose").any { title.contains(it, true) } -> Icons.Default.Favorite
    listOf("Ethik", "Recht", "SGB", "Verfügung").any { title.contains(it, true) } -> Icons.Default.Gavel
    listOf("Kommunikation", "Beratung", "Angehörige", "Familie").any { title.contains(it, true) } -> Icons.Default.Groups
    listOf("Ernährung", "Sondenkost").any { title.contains(it, true) } -> Icons.Default.Restaurant
    listOf("Sturz", "Fraktur", "Arthritis").any { title.contains(it, true) } -> Icons.Default.AccessibilityNew
    else -> Icons.Default.HealthAndSafety
}

@Composable
private fun TopicTile(title: String, available: Boolean, open: (String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 100.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF175C68), Color(0xFF6FA69C)))).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Icon(topicIcon(title), null, Modifier.size(48.dp), tint = Color.White.copy(alpha = .8f))
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("Ausführlich" to "detailed", "Ausgewogen" to "balanced", "Kurz" to "short").forEach { (label, depth) ->
                        OutlinedButton(onClick = { open(depth) }, enabled = available, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
                if (!available) Text("Lernzettel noch nicht veröffentlicht", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteScreen(title: String, depth: String, body: String, back: () -> Unit) {
    val sections = remember(body) { NotePresentation.sections(body) }
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("LERNZETTEL · ${depth.uppercase()}", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Inhalte und aktuelle Vorgaben vor der Anwendung prüfen.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(sections) { section ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        section.heading?.let { heading ->
                            Text(heading, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        section.paragraphs.forEach { paragraph ->
                            Text(paragraph, fontSize = 17.sp, lineHeight = 27.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (sections.isEmpty()) item { Text("Lernzettel nicht verfügbar.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullTextScreen(title: String, body: String, sources: List<String>, back: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(body, style = MaterialTheme.typography.bodyLarge)
            if (sources.isNotEmpty()) Text("Quellen: ${sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayHeader(title: String, summary: String, expanded: Boolean, toggle: () -> Unit) {
    Surface(onClick = toggle, modifier = Modifier.fillMaxWidth().semantics {
        stateDescription = if (expanded) "Ausgeklappt" else "Eingeklappt"
    }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun CardPicker(catalog: LearningCatalog, open: (Int) -> Unit) {
    // Bitset survives configuration changes; closing a day never removes its cards.
    var expandedDays by rememberSaveable { mutableIntStateOf(0) }
    val counts = remember(catalog.cards) { catalog.cards.groupingBy { it.topic }.eachCount() }
    val topics = DefaultCatalogs.defaultPreparations.withIndex()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Karteikarten", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Klappe einen Prüfungstag auf und wähle ein Thema oder mische alle Karten. Tippe beim Lernen auf die Karte, um sie umzudrehen.") }
        item { Button(onClick = { open(-1) }, enabled = catalog.cards.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Alle Themen zufällig mischen") } }
        if (catalog.cards.isEmpty()) item { Text("Noch keine Karteikarten veröffentlicht.") }
        (1..3).forEach { day ->
            val group = topics.filter { it.value.examDayTag == "Tag $day" && (counts[it.value.title] ?: 0) > 0 }
            if (group.isNotEmpty()) {
                val expanded = expandedDays and (1 shl day) != 0
                item(key = "card-day-$day") {
                    DayHeader("Prüfungstag $day", "${group.size} Themen · ${group.sumOf { counts[it.value.title] ?: 0 }} Karten", expanded) {
                        expandedDays = expandedDays xor (1 shl day)
                    }
                }
                if (expanded) items(group, key = { "cards-${it.index}" }) { entry ->
                    ListItem(headlineContent = { Text(entry.value.title) }, supportingContent = { Text("${counts[entry.value.title]} Karten") },
                        modifier = Modifier.clickable { open(entry.index) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardSession(cards: List<PublishedCard>, random: Boolean, back: () -> Unit) {
    val session = remember(cards, random) { if (random) cards.shuffled() else cards }
    var index by remember(session) { mutableIntStateOf(0) }
    var flipped by remember(session, index) { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Karteikarten") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        val card = session.getOrNull(index)
        if (card == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Keine Karten verfügbar.") }
        else Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(card.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${index + 1} / ${session.size}")
            ElevatedCard(onClick = { flipped = !flipped }, modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (flipped) "ANTWORT" else "FRAGE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(if (flipped) card.back else card.front, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text("Zum Umdrehen tippen", style = MaterialTheme.typography.bodySmall)
                        if (flipped) Text("Quelle: ${card.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { index-- }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vorherige Karte") }
                IconButton(onClick = { index++ }, enabled = index < session.lastIndex) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Nächste Karte") }
            }
        }
    }
}

@Composable
private fun CasePicker(catalog: LearningCatalog, completed: Set<String>, setCompleted: (String, Boolean) -> Unit, open: (String) -> Unit) {
    val cases = remember(catalog.cases) { catalog.cases.sortedBy { it.id } }
    var expandedDays by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Fallbeispiele", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Klappe einen Prüfungstag auf. Markierungen werden auf diesem Gerät gespeichert.") }
        if (cases.isEmpty()) item { Text("Noch keine Fallbeispiele veröffentlicht.") }
        listOf(1, 2, 3, 0).forEach { day ->
            val group = cases.filter { if (day == 0) it.examDay == null || it.examDay !in 1..3 else it.examDay == day }
            if (group.isNotEmpty()) {
                val expanded = expandedDays and (1 shl day) != 0
                item(key = "case-day-$day") {
                    DayHeader(if (day == 0) "Weitere Fälle" else "Prüfungstag $day",
                        "${group.size} Fälle · ${group.count { it.id in completed }} bearbeitet", expanded) {
                        expandedDays = expandedDays xor (1 shl day)
                    }
                }
                if (expanded) items(group, key = { it.id }) { case ->
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().clickable { open(case.id) }.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(case.title.ifBlank { case.diseases.joinToString(" · ") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${case.questions.size} Aufgaben · Fall öffnen", style = MaterialTheme.typography.bodySmall)
                            }
                            CaseCompletionToggle(case.id in completed, { setCompleted(case.id, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseCompletionToggle(completed: Boolean, setCompleted: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = completed, role = Role.Checkbox, onValueChange = setCompleted)
        .padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = completed, onCheckedChange = null)
        Text(if (completed) "Bearbeitet" else "Noch nicht bearbeitet", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Preserve paragraph and list boundaries while removing hard PDF line wraps. */
private fun readableCaseText(text: String): String = text.trim().lines().fold(mutableListOf<String>()) { result, raw ->
    val line = raw.trim()
    when {
        line.isEmpty() -> if (result.isNotEmpty() && result.last().isNotEmpty()) result.add("")
        result.isEmpty() || result.last().isEmpty() || line.matches(Regex("^(?:[•●▪]|[0-9]+[.)])\\s.*")) || line == "FALLSITUATION" -> result.add(line)
        result.last() == "FALLSITUATION" -> result.add(line)
        else -> result[result.lastIndex] += " " + line
    }
    result
}.joinToString("\n").trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaseDetail(case: PublishedCase, completed: Boolean, setCompleted: (Boolean) -> Unit, back: () -> Unit) {
    val title = case.title.ifBlank { case.diseases.joinToString(" · ") }
    val sections = remember(case.text) { CasePresentation.sections(case.text) }
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(case.examDay?.let { "FALLBEISPIEL · PRÜFUNGSTAG $it" } ?: "FALLBEISPIEL", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item { CaseCompletionToggle(completed, setCompleted) }
            items(sections) { section ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        section.heading?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                        section.paragraphs.forEach { Text(it, fontSize = 17.sp, lineHeight = 27.sp) }
                    }
                }
            }
            item { Text("Aufgaben", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (case.instructions.isNotBlank()) item { Text(readableCaseText(case.instructions), style = MaterialTheme.typography.bodyMedium) }
            if (case.answerNote.isNotBlank()) item { Text(readableCaseText(case.answerNote), style = MaterialTheme.typography.bodySmall) }
            items(case.questions.size) { i ->
                val question = case.questions[i]
                var expanded by remember(case.id, i) { mutableStateOf(false) }
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Aufgabe ${i + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(readableCaseText(question.question), fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Musterlösung verbergen" else "Musterlösung anzeigen")
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (expanded) {
                        HorizontalDivider()
                        Text(readableCaseText(question.answer), fontSize = 17.sp, lineHeight = 27.sp)
                    }
                } }
            }
        }
    }
}

@Composable
private fun QuizScreen(catalog: LearningCatalog) {
    val dates = remember(catalog.quizzes) { catalog.quizzes.map { it.published }.distinct().sortedDescending() }
    var selectedDate by remember(dates) { mutableStateOf(dates.firstOrNull()) }
    val quiz = remember(catalog.quizzes, selectedDate) { catalog.quizzes.filter { it.published == selectedDate }.sortedBy { it.id } }
    var index by remember(quiz) { mutableIntStateOf(0) }
    var choice by remember(quiz, index) { mutableIntStateOf(-1) }
    var correct by remember(quiz) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Multiple-Choice-Quiz", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Vier Antwortmöglichkeiten · eine Antwort ist richtig", style = MaterialTheme.typography.bodyMedium)
        if (dates.isNotEmpty()) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("Quiz: $selectedDate · ${quiz.size} Fragen"); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    dates.forEach { date -> DropdownMenuItem(text = { Text("$date · ${catalog.quizzes.count { it.published == date }} Fragen") }, onClick = { selectedDate = date; menuOpen = false }) }
                }
            }
        }
        if (quiz.isEmpty()) Text("Noch keine Quizfragen veröffentlicht.")
        else if (index >= quiz.size) { Text("Übung beendet: $correct von ${quiz.size} richtig.", style = MaterialTheme.typography.titleLarge); Text("Eine Ranglistenwertung ist noch nicht eingerichtet."); OutlinedButton(onClick = { index = 0; correct = 0 }) { Text("Erneut üben") } }
        else {
            val q = quiz[index]
            Text("${index + 1} / ${quiz.size} · ${q.topic}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(q.question, style = MaterialTheme.typography.titleLarge)
            q.options.forEachIndexed { option, text ->
                OutlinedButton(onClick = { if (choice < 0) { choice = option; if (option == q.correctIndex) correct++ } }, enabled = choice < 0, modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.fillMaxWidth()) }
            }
            if (choice >= 0) {
                Text(if (choice == q.correctIndex) "Richtig" else "Nicht richtig. Richtig ist: ${q.options[q.correctIndex]}", fontWeight = FontWeight.Bold)
                Text(q.explanation); Text("Quelle: ${q.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { index++ }) { Text("Nächste Frage") }
            }
            Text("Aktuell nur Übung: Für eine faire Rangliste muss die Antwort serverseitig gewertet werden.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleInfo(title: String, body: String, back: () -> Unit) = FullTextScreen(title, body, emptyList(), back)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettings(store: PublishedContentStore, back: () -> Unit) {
    val context = LocalContext.current
    val catalog by store.catalog.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Lerninhalte", style = MaterialTheme.typography.titleLarge)
            Text("Inhaltsstand: ${catalog.revision}. ${if (PublishedContentStore.CONTENT_URL.isBlank()) "Automatische Updates sind noch nicht eingerichtet." else "Updates werden bei verfügbarer Verbindung regelmäßig geprüft."}")
            HorizontalDivider()
            Text("Darstellung & Bedienung", style = MaterialTheme.typography.titleLarge)
            Text("Schriftgröße, Kontrast und weitere Bedienhilfen kannst du in den Android-Einstellungen ändern.")
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Android-Bedienhilfen öffnen") }
            HorizontalDivider()
            Text("Datenschutz", style = MaterialTheme.typography.titleLarge)
            Text("Für die spätere Rangliste ist eine gesonderte Einrichtung erforderlich. Bisher werden keine alten Lernpunkte übermittelt.")
        }
    }
}
