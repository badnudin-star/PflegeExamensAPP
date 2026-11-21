package de.pflegeexamen.app.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
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
import de.pflegeexamen.app.data.progress.CardProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCardProgress
import de.pflegeexamen.app.data.progress.CaseProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCaseProgress
import de.pflegeexamen.app.data.progress.QuizProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesQuizProgress
import de.pflegeexamen.app.domain.content.*
import de.pflegeexamen.app.domain.cases.CasePresentation
import de.pflegeexamen.app.domain.cards.CardVisibility
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.notes.NotePresentation
import kotlinx.coroutines.launch

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
    val cardProgress = remember(context.applicationContext) { CardProgressStore(SharedPreferencesCardProgress(context)) }
    val learnedCards by cardProgress.learned.collectAsStateWithLifecycle()
    val quizProgress = remember(context.applicationContext) { QuizProgressStore(SharedPreferencesQuizProgress(context)) }
    var showLearned by rememberSaveable { mutableStateOf(false) }
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
            composable("topics") { Topics(catalog) { index, depth, query -> nav.navigate("note/$index/$depth?query=${Uri.encode(query)}") } }
            composable("note/{index}/{depth}?query={query}", arguments = listOf(
                androidx.navigation.navArgument("query") { defaultValue = "" }
            )) { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val depth = entry.arguments?.getString("depth").orEmpty()
                val note = catalog.notes.firstOrNull { it.topic == topic && it.formatVersion == 2 }
                val content = if (depth == "simple") note?.short else note?.detailed
                val depthLabel = if (depth == "simple") "Vereinfacht" else "Lernzettel"
                NoteScreen(topic ?: "Lernzettel", depthLabel, content.orEmpty(), entry.arguments?.getString("query").orEmpty()) { nav.popBackStack() }
            }
            composable("cards") { CardPicker(catalog, learnedCards, showLearned, { showLearned = it }) { nav.navigate("card_session/$it") } }
            composable("card_session/{index}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val available = if (index == -1) catalog.cards else catalog.cards.filter { it.topic == topic }
                CardSession(available, index == -1, learnedCards, showLearned, cardProgress::setLearned) { nav.popBackStack() }
            }
            composable("cases") { CasePicker(catalog, completedCases, caseProgress::setCompleted) { nav.navigate("case/${Uri.encode(it)}") } }
            composable("case/{id}") { entry ->
                val case = catalog.cases.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (case != null) CaseDetail(case, case.id in completedCases, { caseProgress.setCompleted(case.id, it) }) { nav.popBackStack() } else FullTextScreen("Fallbeispiel", "Nicht verfügbar.", emptyList()) { nav.popBackStack() }
            }
            composable("quiz") { QuizScreen(catalog, quizProgress) }
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
private fun Topics(catalog: LearningCatalog, open: (Int, String, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val topics = DefaultCatalogs.defaultPreparations
    val term = query.trim()
    fun contentMatch(text: String): Boolean = term.isNotEmpty() && NotePresentation.firstMatch(NotePresentation.blocks(text), term) != null
    fun matches(entry: de.pflegeexamen.app.domain.model.DefaultPreparationTemplate): Boolean {
        val note = catalog.notes.firstOrNull { it.topic == entry.title && it.formatVersion == 2 }
        return entry.title.contains(term, true) || entry.keywordsCsv.contains(term, true) ||
            (note != null && (contentMatch(note.detailed) || contentMatch(note.short)))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Deine Themen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Tippe auf ein Thema für den Lernzettel oder auf den Bereich darunter für die vereinfachte Version.", style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Themen und Lernzettel durchsuchen") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }) }
        listOf("Tag 1", "Tag 2", "Tag 3").forEach { day ->
            val group = topics.withIndex().filter { it.value.examDayTag == day && matches(it.value) }
            if (group.isNotEmpty()) {
                item(key = "heading-$day") {
                    Surface(onClick = { collapsed[day] = !(collapsed[day] ?: false) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${group.size} Themen"); Icon(if (collapsed[day] == true && term.isEmpty()) Icons.Default.ExpandMore else Icons.Default.ExpandLess, "Ein- oder ausklappen")
                        }
                    }
                }
                if (collapsed[day] != true || term.isNotEmpty()) items(group, key = { "topic-${it.index}" }) { entry ->
                    val note = catalog.notes.firstOrNull { it.topic == entry.value.title && it.formatVersion == 2 }
                    TopicTile(entry.value.title, note != null, note != null && contentMatch(note.detailed), note != null && contentMatch(note.short)) {
                        open(entry.index, it, term)
                    }
                }
            }
        }
        if (topics.none { matches(it) }) item { Text("Keine passenden Themen oder Lernzettel gefunden.") }
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
private fun TopicTile(title: String, available: Boolean, mainMatch: Boolean, simpleMatch: Boolean, open: (String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 100.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF175C68), Color(0xFF6FA69C))))
                    .clickable(enabled = available, onClickLabel = "Lernzettel öffnen") { open("main") }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (mainMatch) Text("Suchtreffer im Lernzettel", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    if (!available) Text("Lernzettel noch nicht verfügbar", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                Icon(topicIcon(title), null, Modifier.size(48.dp), tint = Color.White.copy(alpha = .8f))
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = available, onClickLabel = "Vereinfachten Lernzettel öffnen") { open("simple") }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vereinfachte Lernzettel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (simpleMatch) Text("Suchtreffer im vereinfachten Lernzettel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteScreen(title: String, depth: String, body: String, query: String, back: () -> Unit) {
    val blocks = remember(body) { NotePresentation.blocks(body) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var positioned by remember(body, query) { mutableStateOf(false) }
    val match = remember(blocks, query) { NotePresentation.firstMatch(blocks, query) }
    LaunchedEffect(match, body, query) { if (match != null) listState.scrollToItem(match + 1) }
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("LERNZETTEL · ${depth.uppercase()}", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Inhalte und aktuelle Vorgaben vor der Anwendung prüfen.", style = MaterialTheme.typography.bodySmall)
                }
            }
            itemsIndexed(blocks) { blockIndex, block ->
                val term = query.trim()
                val highlight = MaterialTheme.colorScheme.secondaryContainer
                val display = remember(block.text, term, highlight) { buildAnnotatedString {
                    var pos = 0
                    if (term.isNotEmpty()) {
                        var found = block.text.indexOf(term, pos, ignoreCase = true)
                        while (found >= 0) {
                            append(block.text.substring(pos, found))
                            withStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Bold)) { append(block.text.substring(found, found + term.length)) }
                            pos = found + term.length
                            found = block.text.indexOf(term, pos, ignoreCase = true)
                        }
                    }
                    append(block.text.substring(pos))
                } }
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text(display, Modifier.fillMaxWidth().padding(if (block.isHeading) 14.dp else 18.dp),
                        style = if (block.isHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontSize = if (block.isHeading) 18.sp else 17.sp, lineHeight = 27.sp,
                        fontWeight = if (block.isHeading) FontWeight.Bold else FontWeight.Normal,
                        color = if (block.isHeading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        onTextLayout = { layout ->
                            if (!positioned && blockIndex == match && term.isNotEmpty()) {
                                val offset = block.text.indexOf(term, ignoreCase = true)
                                if (offset >= 0) {
                                    positioned = true
                                    val y = layout.getBoundingBox(offset).top.toInt().coerceAtLeast(0)
                                    scope.launch { listState.scrollToItem(blockIndex + 1, y) }
                                }
                            }
                        })
                }
            }
            if (blocks.isEmpty()) item { Text("Lernzettel nicht verfügbar.") }
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
private fun CardPicker(catalog: LearningCatalog, learned: Set<String>, showLearned: Boolean, setShowLearned: (Boolean) -> Unit, open: (Int) -> Unit) {
    var expandedDays by rememberSaveable { mutableIntStateOf(0) }
    val visible = remember(catalog.cards, learned, showLearned) { CardVisibility.visible(catalog.cards, learned, showLearned) }
    val counts = remember(visible) { visible.groupingBy { it.topic }.eachCount() }
    val allCounts = remember(catalog.cards) { catalog.cards.groupingBy { it.topic }.eachCount() }
    val learnedCounts = remember(catalog.cards, learned) { catalog.cards.filter { it.id in learned }.groupingBy { it.topic }.eachCount() }
    val topics = DefaultCatalogs.defaultPreparations.withIndex()
    val learnedCount = catalog.cards.count { it.id in learned }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Karteikarten", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Markiere Karten beim Lernen als gelernt. Sie werden ausgeblendet, bis du sie wieder einblendest.") }
        item {
            Row(Modifier.fillMaxWidth().toggleable(value = showLearned, role = Role.Switch, onValueChange = setShowLearned)
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gelernte Karten anzeigen", fontWeight = FontWeight.SemiBold)
                    Text("$learnedCount gelernt · Markierungen auf diesem Gerät", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = showLearned, onCheckedChange = null)
            }
        }
        item { Button(onClick = { open(-1) }, enabled = visible.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Alle sichtbaren Karten zufällig mischen") } }
        if (catalog.cards.isEmpty()) item { Text("Noch keine Karteikarten veröffentlicht.") }
        else if (visible.isEmpty()) item { Text("Alle Karten sind ausgeblendet. Aktiviere „Gelernte Karten anzeigen“, um sie wiederzusehen.") }
        (1..3).forEach { day ->
            val group = topics.filter { it.value.examDayTag == "Tag $day" && (allCounts[it.value.title] ?: 0) > 0 }
            if (group.isNotEmpty()) {
                val expanded = expandedDays and (1 shl day) != 0
                item(key = "card-day-$day") {
                    DayHeader("Prüfungstag $day", "${group.size} Themen · ${group.sumOf { counts[it.value.title] ?: 0 }} sichtbar", expanded) {
                        expandedDays = expandedDays xor (1 shl day)
                    }
                }
                if (expanded) items(group, key = { "cards-${it.index}" }) { entry ->
                    val count = counts[entry.value.title] ?: 0
                    val learnedCountForTopic = learnedCounts[entry.value.title] ?: 0
                    ListItem(headlineContent = { Text(entry.value.title) }, supportingContent = { Text("$count sichtbar · $learnedCountForTopic gelernt") },
                        modifier = Modifier.clickable(enabled = count > 0) { open(entry.index) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardSession(cards: List<PublishedCard>, random: Boolean, learned: Set<String>, showLearned: Boolean, setLearned: (String, Boolean) -> Unit, back: () -> Unit) {
    // Keep one stable order for the session when learned IDs change (especially in random mode).
    val order = remember(cards, random) { if (random) cards.shuffled() else cards }
    val session = remember(order, learned, showLearned) { CardVisibility.visible(order, learned, showLearned) }
    var index by remember(order, showLearned) { mutableIntStateOf(0) }
    val card = session.getOrNull(index)
    var flipped by remember(card?.id) { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Karteikarten") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        if (card == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keine sichtbaren Karten mehr. Du kannst gelernte Karten in der Übersicht wieder einblenden.", textAlign = TextAlign.Center)
                OutlinedButton(onClick = back) { Text("Zur Kartenübersicht") }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${index + 1} / ${session.size}" + if (card.id in learned) " · Gelernt" else "")
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
            OutlinedButton(onClick = {
                val markLearned = card.id !in learned
                if (markLearned && !showLearned) index = CardVisibility.indexAfterRemoval(index, session.size)
                setLearned(card.id, markLearned)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (card.id in learned) "Wieder lernen · einblenden" else if (showLearned) "Als gelernt markieren" else "Gelernt · Karte ausblenden")
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
private fun QuizScreen(catalog: LearningCatalog, progress: QuizProgressStore) {
    val dates = remember(catalog.quizzes) { catalog.quizzes.map { it.published }.distinct().sortedDescending() }
    var selectedDate by rememberSaveable(dates) { mutableStateOf(dates.firstOrNull()) }
    val quiz = remember(catalog.quizzes, selectedDate) { catalog.quizzes.filter { it.published == selectedDate }.sortedBy { it.id } }
    var session by remember(selectedDate, quiz, progress) { mutableStateOf(selectedDate?.let { progress.load(it, quiz) }) }
    var confirmRestart by remember(selectedDate, quiz) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun update(next: de.pflegeexamen.app.domain.quiz.QuizSession) {
        selectedDate?.let { progress.save(it, quiz, next) }
        session = next
    }
    if (confirmRestart) {
        AlertDialog(onDismissRequest = { confirmRestart = false }, title = { Text("Quiz neu beginnen?") },
            text = { Text("Dein gespeicherter Übungsfortschritt für dieses Quiz wird zurückgesetzt.") },
            confirmButton = { TextButton(onClick = {
                selectedDate?.let { progress.reset(it); session = progress.load(it, quiz) }
                confirmRestart = false
                scope.launch { scroll.scrollTo(0) }
            }) { Text("Neu beginnen") } },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Abbrechen") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Multiple-Choice-Quiz", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Vier Antwortmöglichkeiten · eine Antwort ist richtig", style = MaterialTheme.typography.bodyMedium)
        Text("Dein Übungsfortschritt wird nur auf diesem Gerät gespeichert und nicht für die Rangliste gewertet.", style = MaterialTheme.typography.bodySmall)
        if (dates.isNotEmpty()) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("Quiz: $selectedDate · ${quiz.size} Fragen"); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    dates.forEach { date -> DropdownMenuItem(text = { Text("$date · ${catalog.quizzes.count { it.published == date }} Fragen") }, onClick = { selectedDate = date; menuOpen = false; scope.launch { scroll.scrollTo(0) } }) }
                }
            }
        }
        val current = session
        if (quiz.isEmpty() || current == null) Text("Noch keine Quizfragen veröffentlicht.")
        else if (current.index >= quiz.size) {
            Text("Übung beendet: ${current.correctCount(quiz)} von ${quiz.size} richtig.", style = MaterialTheme.typography.titleLarge)
            Text("Eine Ranglistenwertung ist noch nicht eingerichtet.")
            OutlinedButton(onClick = { confirmRestart = true }) { Text("Erneut üben") }
        } else {
            val q = quiz[current.index]
            val choice = current.answers.getOrNull(current.index)
            Text("${current.index + 1} / ${quiz.size} · ${q.topic}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(q.question, style = MaterialTheme.typography.titleLarge)
            q.options.forEachIndexed { option, text ->
                OutlinedButton(onClick = { update(current.answer(option, quiz)) }, enabled = choice == null, modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.fillMaxWidth()) }
            }
            if (choice != null) {
                Text(if (choice == q.correctIndex) "Richtig" else "Nicht richtig. Richtig ist: ${q.options[q.correctIndex]}", fontWeight = FontWeight.Bold)
                Text(q.explanation); Text("Quelle: ${q.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { update(current.advance(quiz)); scope.launch { scroll.scrollTo(0) } }) { Text("Nächste Frage") }
            }
            if (current.answers.isNotEmpty()) OutlinedButton(onClick = { confirmRestart = true }) { Text("Quiz neu beginnen") }
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
