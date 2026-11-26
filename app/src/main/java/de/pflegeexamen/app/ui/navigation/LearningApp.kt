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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.pflegeexamen.app.BuildConfig
import de.pflegeexamen.app.data.quiz.HttpsRankingTransport
import de.pflegeexamen.app.data.quiz.AndroidQuizArchive
import de.pflegeexamen.app.data.quiz.QuizRankingClient
import de.pflegeexamen.app.data.quiz.RankingLoad
import de.pflegeexamen.app.data.content.PublishedContentStore
import de.pflegeexamen.app.data.content.ContentRefreshResult
import de.pflegeexamen.app.data.progress.CardProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCardProgress
import de.pflegeexamen.app.data.progress.CaseProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesCaseProgress
import de.pflegeexamen.app.data.progress.QuizProgressStore
import de.pflegeexamen.app.data.progress.SharedPreferencesQuizProgress
import de.pflegeexamen.app.domain.content.*
import de.pflegeexamen.app.domain.cases.CasePresentation
import de.pflegeexamen.app.domain.cases.CaseSearchIndex
import de.pflegeexamen.app.domain.cards.CardVisibility
import de.pflegeexamen.app.domain.cards.CardSessionState
import de.pflegeexamen.app.domain.cards.cardDeckFingerprint
import de.pflegeexamen.app.domain.cards.TopicCardCounts
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.notes.NotePresentation
import de.pflegeexamen.app.domain.notes.NoteHeading
import de.pflegeexamen.app.domain.notes.NoteMatch
import de.pflegeexamen.app.domain.notes.NoteSearchIndex
import de.pflegeexamen.app.domain.notes.NoteSearchHit
import de.pflegeexamen.app.domain.quiz.berlinQuizDay
import de.pflegeexamen.app.domain.quiz.loadedQuizDayMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("home", "Heute", Icons.Default.Home), Destination("topics", "Themen", Icons.Default.MenuBook),
    Destination("cards", "Karten", Icons.Default.Style), Destination("cases", "Fallbeispiele", Icons.Default.Assignment),
    Destination("quiz", "Quiz", Icons.Default.Quiz)
)

@Composable
fun LearningApp(store: PublishedContentStore) {
    val catalog by store.catalog.collectAsStateWithLifecycle()
    LearningAppContent(catalog, store)
}

/** Kept separate so navigation can be exercised with synthetic catalogs on the emulator. */
@Composable
internal fun LearningAppContent(catalog: LearningCatalog, store: PublishedContentStore) {
    val context = LocalContext.current
    val caseProgress = remember(context.applicationContext) { CaseProgressStore(SharedPreferencesCaseProgress(context)) }
    val completedCases by caseProgress.completed.collectAsStateWithLifecycle()
    val cardProgress = remember(context.applicationContext) { CardProgressStore(SharedPreferencesCardProgress(context)) }
    val learnedCards by cardProgress.learned.collectAsStateWithLifecycle()
    val quizProgress = remember(context.applicationContext) { QuizProgressStore(SharedPreferencesQuizProgress(context)) }
    val quizArchive = remember(context.applicationContext) { AndroidQuizArchive.create(context) }
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
                val counts = remember(catalog.cards, topic, learnedCards, showLearned) {
                    topic?.let { CardVisibility.topicCounts(catalog.cards, it, learnedCards, showLearned) }
                }
                NoteScreen(topic ?: "Lernzettel", depthLabel, content.orEmpty(), entry.arguments?.getString("query").orEmpty(),
                    counts = counts,
                    openCards = { nav.navigate("card_session/$index") },
                    manageLearned = { nav.navigate("learned_cards/topic/$index") },
                    back = { nav.popBackStack() },
                    pdfSource = note?.sources?.getOrNull(if (depth == "simple") 1 else 0))
            }
            composable("cards") {
                CardPicker(catalog, learnedCards, showLearned, { showLearned = it },
                    manageLearned = { nav.navigate("learned_cards") }, open = { index, query ->
                        nav.navigate("card_session/$index?query=${Uri.encode(query)}")
                    })
            }
            composable("learned_cards") {
                LearnedCardsScreen(catalog.cards, learnedCards, cardProgress::setLearned) { nav.popBackStack() }
            }
            composable("learned_cards/topic/{index}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val scopedCards = remember(catalog.cards, topic) { catalog.cards.filter { topic != null && it.topic == topic } }
                LearnedCardsScreen(scopedCards, learnedCards, cardProgress::setLearned, topic = topic) { nav.popBackStack() }
            }
            composable("card_session/{index}?query={query}", arguments = listOf(
                androidx.navigation.navArgument("query") { defaultValue = "" }
            )) { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: -1
                val topic = DefaultCatalogs.defaultPreparations.getOrNull(index)?.title
                val query = entry.arguments?.getString("query").orEmpty()
                val available = remember(catalog.cards, index, topic, query) {
                    CardVisibility.matching(catalog.cards, query).filter { index == -1 || it.topic == topic }
                }
                CardSession(available, index == -1, learnedCards, showLearned, cardProgress::setLearned, query) { nav.popBackStack() }
            }
            composable("cases") { CasePicker(catalog, completedCases, caseProgress::setCompleted) { nav.navigate("case/${Uri.encode(it)}") } }
            composable("case/{id}") { entry ->
                val case = catalog.cases.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (case != null) CaseDetail(case, case.id in completedCases, { caseProgress.setCompleted(case.id, it) },
                    openDayTopics = { day -> nav.navigate("case_topics/$day") }, back = { nav.popBackStack() })
                else FullTextScreen("Fallbeispiel", "Nicht verfügbar.", emptyList()) { nav.popBackStack() }
            }
            composable("case_topics/{day}") { entry ->
                val day = entry.arguments?.getString("day")?.toIntOrNull()
                CaseDayTopics(catalog, day, open = { index, depth -> nav.navigate("note/$index/$depth") }, back = { nav.popBackStack() })
            }
            composable("quiz") { QuizScreen(catalog, quizProgress, openArchive = { nav.navigate("quiz_archive") }) { index, depth -> nav.navigate("note/$index/$depth") } }
            composable("quiz_archive") { QuizArchivePicker(quizArchive, catalog.quizzes.map { it.published }.toSet(), open = { nav.navigate("quiz_archive/$it") }, back = { nav.popBackStack() }) }
            composable("quiz_archive/{date}") { entry ->
                ArchivedQuizScreen(entry.arguments?.getString("date").orEmpty(), quizArchive, catalog, quizProgress,
                    openNote = { index, depth -> nav.navigate("note/$index/$depth") }, back = { nav.popBackStack() })
            }
            composable("ranking") { QuizRankingScreen(openParticipation = { nav.navigate("ranked_quiz") }) { nav.popBackStack() } }
            composable("ranked_quiz") {
                if (BuildConfig.ENABLE_RANKED_PARTICIPATION && BuildConfig.RANKING_URL.isNotBlank()) {
                    RankedParticipation(BuildConfig.RANKING_URL) { nav.popBackStack() }
                } else {
                    Text("Die Wertungsteilnahme ist noch nicht eingerichtet.")
                }
            }
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
internal fun Topics(catalog: LearningCatalog, open: (Int, String, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val topics = DefaultCatalogs.defaultPreparations
    val term = query.trim()
    // Parsing PDF-derived display blocks is expensive: build the index once per
    // catalog off the UI thread, and discard results for superseded catalogs/queries.
    val indexed by produceState<Pair<List<PublishedNote>, NoteSearchIndex>?>(null, catalog.notes) {
        val notes = catalog.notes
        value = null
        val index = withContext(Dispatchers.Default) { NoteSearchIndex(notes) }
        value = notes to index
    }
    val index = indexed?.takeIf { it.first == catalog.notes }?.second
    val searched by produceState<Triple<NoteSearchIndex, String, Map<String, NoteSearchHit>>?>(null, index, term) {
        value = null
        if (index != null && term.isNotEmpty()) {
            val hits = withContext(Dispatchers.Default) { index.search(term) }
            value = Triple(index, term, hits)
        }
    }
    val hits = searched?.takeIf { it.first === index && it.second == term }?.third
    val searching = term.isNotEmpty()
    val waiting = searching && hits == null
    val notesByTopic = remember(catalog.notes) { catalog.notes.filter { it.formatVersion == 2 }.associateBy { it.topic } }
    fun matches(entry: de.pflegeexamen.app.domain.model.DefaultPreparationTemplate): Boolean =
        !searching || entry.title.contains(term, true) || entry.keywordsCsv.contains(term, true) || hits?.containsKey(entry.title) == true
    LazyColumn(Modifier.fillMaxSize().testTag("topicList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Deine Themen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Tippe auf ein Thema für den Lernzettel oder auf den Bereich darunter für die vereinfachte Version.", style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Themen und Lernzettel durchsuchen") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }) }
        if (waiting) item { Text("Lernzettel werden durchsucht …") }
        if (!waiting) listOf("Tag 1", "Tag 2", "Tag 3").forEach { day ->
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
                    val note = notesByTopic[entry.value.title]
                    val hit = hits?.get(entry.value.title)
                    TopicTile(entry.value.title, note != null, hit?.main == true, hit?.simple == true) {
                        open(entry.index, it, term)
                    }
                }
            }
        }
        if (!waiting && topics.none { matches(it) }) item { Text("Keine passenden Themen oder Lernzettel gefunden.") }
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

private data class NoteJump(val blockIndex: Int, val offset: Int?, val sequence: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteScreen(title: String, depth: String, body: String, query: String, counts: TopicCardCounts?,
    openCards: () -> Unit, manageLearned: () -> Unit, back: () -> Unit, pdfSource: String? = null) {
    var showFullText by rememberSaveable(body) { mutableStateOf(false) }
    var showPdf by rememberSaveable(body, pdfSource) { mutableStateOf(false) }
    if (showPdf && pdfSource != null) NotePdfDialog(pdfSource, body) { showPdf = false }
    val blocks = remember(body, showFullText) {
        if (showFullText) NotePresentation.fullTextBlocks(body) else NotePresentation.blocks(body)
    }
    val headings = remember(blocks) { NotePresentation.headings(blocks) }
    var showContents by rememberSaveable(body) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var positioned by rememberSaveable(body, query, showFullText) { mutableStateOf(false) }
    val firstMatch = remember(blocks, query) { NotePresentation.nextMatch(blocks, query) }
    var selectedBlock by rememberSaveable(body, query, showFullText) { mutableIntStateOf(firstMatch?.blockIndex ?: -1) }
    var selectedOffset by rememberSaveable(body, query, showFullText) { mutableIntStateOf(firstMatch?.offset ?: -1) }
    val selected = remember(blocks, query, selectedBlock, selectedOffset, firstMatch) {
        NoteMatch(selectedBlock, selectedOffset).takeIf { NotePresentation.isMatch(blocks, query, it) } ?: firstMatch
    }
    val previous = remember(blocks, query, selected) { selected?.let { NotePresentation.previousMatch(blocks, query, it) } }
    val next = remember(blocks, query, selected) { selected?.let { NotePresentation.nextMatch(blocks, query, it) } }
    // Text layout offsets allow jumps to a particular occurrence inside a long paragraph.
    // Only composed items are retained, and pending jumps are cancelled by later choices.
    val layouts = remember(body, showFullText) { mutableStateMapOf<Int, TextLayoutResult>() }
    val density = LocalDensity.current
    var sequence by remember(body, query, showFullText) { mutableIntStateOf(0) }
    var jump by remember(body, query, showFullText) { mutableStateOf<NoteJump?>(null) }
    fun jumpTo(blockIndex: Int, offset: Int?) {
        sequence++
        jump = NoteJump(blockIndex, offset, sequence)
    }
    fun selectMatch(match: NoteMatch) {
        selectedBlock = match.blockIndex
        selectedOffset = match.offset
        positioned = true
        jumpTo(match.blockIndex, match.offset)
    }
    LaunchedEffect(body, query, firstMatch, showFullText) {
        if (!positioned && firstMatch != null) jumpTo(firstMatch.blockIndex, firstMatch.offset)
    }
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        listState.scrollToItem(target.blockIndex + 1)
        if (target.offset != null) {
            val layout = snapshotFlow { layouts[target.blockIndex] }.filterNotNull().first()
            val inset = with(density) { (if (blocks[target.blockIndex].isHeading) 14.dp else 18.dp).roundToPx() }
            val y = layout.getBoundingBox(target.offset).top.toInt().coerceAtLeast(0) + inset
            listState.scrollToItem(target.blockIndex + 1, y)
        }
        positioned = true
    }
    if (showContents && headings.isNotEmpty()) {
        NoteContentsDialog(headings, onDismiss = { showContents = false }) { blockIndex ->
            showContents = false
            // An explicit choice cancels an outstanding search jump.
            positioned = true
            jumpTo(blockIndex, null)
        }
    }
    Scaffold(topBar = { Column {
        TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
        }, actions = {
            if (headings.isNotEmpty()) IconButton(onClick = { showContents = true }) {
                Icon(Icons.Default.List, contentDescription = "Überschriften öffnen")
            }
        })
        if (query.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected == null) "Kein Treffer in dieser Lernzettelversion" else "Suchtreffer: ${query.trim()}",
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                if (selected != null) {
                    IconButton(onClick = { previous?.let(::selectMatch) }, enabled = previous != null) {
                        Icon(Icons.Default.KeyboardArrowUp, "Vorheriger Suchtreffer")
                    }
                    IconButton(onClick = { next?.let(::selectMatch) }, enabled = next != null) {
                        Icon(Icons.Default.KeyboardArrowDown, "Nächster Suchtreffer")
                    }
                }
            }
        }
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("LERNZETTEL · ${depth.uppercase()}", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Inhalte und aktuelle Vorgaben vor der Anwendung prüfen.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        showFullText = !showFullText
                        scope.launch { listState.scrollToItem(0) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showFullText) "Aufbereitete Ansicht" else "Vollständigen Text anzeigen")
                    }
                    if (showFullText) Text("Vollständiger extrahierter Text inklusive Quellen. Bilder und PDF-Layout sind nicht enthalten.", style = MaterialTheme.typography.bodySmall)
                    if (pdfSource != null && notePdfName(pdfSource) != null) {
                        OutlinedButton(onClick = { showPdf = true }, modifier = Modifier.fillMaxWidth()) { Text("Original-PDF anzeigen") }
                        Text("Das Original ist nur verfügbar, wenn es zum angezeigten Text passt und in dieser App-Version enthalten ist.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (counts != null) {
                        if (counts.total == 0) {
                            Text("Für dieses Thema sind keine Karteikarten verfügbar.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Karteikarten: ${counts.visible} sichtbar · ${counts.learned} gelernt", style = MaterialTheme.typography.bodyMedium)
                            if (counts.visible > 0) {
                                OutlinedButton(onClick = openCards, modifier = Modifier.fillMaxWidth()) { Text("Karteikarten zu diesem Thema lernen") }
                            } else {
                                Text("Alle Karten dieses Themas sind ausgeblendet.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (counts.learned > 0) {
                                TextButton(onClick = manageLearned) { Text("Gelernte Karten dieses Themas verwalten") }
                            }
                        }
                    }
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
                DisposableEffect(blockIndex, block.text, layouts) {
                    onDispose { layouts.remove(blockIndex) }
                }
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text(display, Modifier.fillMaxWidth().padding(if (block.isHeading) 14.dp else 18.dp),
                        style = if (block.isHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontSize = if (block.isHeading) 18.sp else 17.sp, lineHeight = 27.sp,
                        fontWeight = if (block.isHeading) FontWeight.Bold else FontWeight.Normal,
                        color = if (block.isHeading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        onTextLayout = { layout -> layouts[blockIndex] = layout })
                }
            }
            if (blocks.isEmpty()) item { Text("Lernzettel nicht verfügbar.") }
        }
    }
}

@Composable
private fun NoteContentsDialog(headings: List<NoteHeading>, onDismiss: () -> Unit, open: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Überschriften") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatisch erkannte Überschriften; die Liste kann unvollständig sein.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    headings.forEach { heading ->
                        TextButton(onClick = { open(heading.blockIndex) }, modifier = Modifier.fillMaxWidth()) {
                            Text(heading.text, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
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
internal fun CardPicker(catalog: LearningCatalog, learned: Set<String>, showLearned: Boolean, setShowLearned: (Boolean) -> Unit, manageLearned: () -> Unit, open: (Int, String) -> Unit) {
    var expandedDays by rememberSaveable { mutableIntStateOf(0) }
    var collapsedSearchDays by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val searching = term.isNotEmpty()
    val matching = remember(catalog.cards, term) { CardVisibility.matching(catalog.cards, term) }
    val visible = remember(matching, learned, showLearned) { CardVisibility.visible(matching, learned, showLearned) }
    val counts = remember(visible) { visible.groupingBy { it.topic }.eachCount() }
    val allCounts = remember(matching) { matching.groupingBy { it.topic }.eachCount() }
    val learnedCounts = remember(matching, learned) { matching.filter { it.id in learned }.groupingBy { it.topic }.eachCount() }
    val topics = DefaultCatalogs.defaultPreparations.withIndex()
    val learnedCount = catalog.cards.count { it.id in learned }
    LazyColumn(Modifier.fillMaxSize().testTag("cardPickerList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Karteikarten", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Markiere Karten beim Lernen als gelernt. Sie werden ausgeblendet, bis du sie wieder einblendest.") }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Karteikarten durchsuchen") },
                supportingText = { Text("Suche in Thema, Frage und Antwort · Lösungen bleiben verdeckt") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Kartensuche löschen") } })
        }
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
        item {
            OutlinedButton(onClick = manageLearned, modifier = Modifier.fillMaxWidth()) {
                Text("Gelernte Karten einzeln wieder einblenden ($learnedCount)")
            }
        }
        item { Button(onClick = { open(-1, term) }, enabled = visible.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (searching) "Passende sichtbare Karten zufällig mischen" else "Alle sichtbaren Karten zufällig mischen")
        } }
        if (catalog.cards.isEmpty()) item { Text("Noch keine Karteikarten veröffentlicht.") }
        else if (matching.isEmpty()) item { Text("Keine passenden Karteikarten gefunden.") }
        else if (visible.isEmpty()) item { Text(if (searching) "Alle passenden Karten sind ausgeblendet. Aktiviere „Gelernte Karten anzeigen“, um sie wiederzusehen." else "Alle Karten sind ausgeblendet. Aktiviere „Gelernte Karten anzeigen“, um sie wiederzusehen.") }
        if (searching && matching.isNotEmpty()) item { Text("${visible.size} passende ${if (visible.size == 1) "Karte" else "Karten"} sichtbar · ${matching.size - visible.size} ausgeblendet", style = MaterialTheme.typography.bodySmall) }
        (1..3).forEach { day ->
            val group = topics.filter { it.value.examDayTag == "Tag $day" && (allCounts[it.value.title] ?: 0) > 0 }
            if (group.isNotEmpty()) {
                val expanded = if (searching) collapsedSearchDays and (1 shl day) == 0 else expandedDays and (1 shl day) != 0
                item(key = "card-day-$day") {
                    DayHeader("Prüfungstag $day", "${group.size} Themen · ${group.sumOf { counts[it.value.title] ?: 0 }} ${if (searching) "passend und sichtbar" else "sichtbar"}", expanded) {
                        if (searching) collapsedSearchDays = collapsedSearchDays xor (1 shl day) else expandedDays = expandedDays xor (1 shl day)
                    }
                }
                if (expanded) items(group, key = { "cards-${it.index}" }) { entry ->
                    val count = counts[entry.value.title] ?: 0
                    val learnedCountForTopic = learnedCounts[entry.value.title] ?: 0
                    ListItem(headlineContent = { Text(entry.value.title) }, supportingContent = { Text("$count sichtbar · $learnedCountForTopic gelernt${if (searching) " · Suchergebnis" else ""}") },
                        modifier = Modifier.clickable(enabled = count > 0) { open(entry.index, term) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearnedCardsScreen(cards: List<PublishedCard>, learned: Set<String>, setLearned: (String, Boolean) -> Unit, topic: String? = null, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(cards, learned, query) { CardVisibility.learnedMatching(cards, learned, query) }
    val count = remember(cards, learned) { cards.count { it.id in learned } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Gelernte Karten") }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
        }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Karten wieder einblenden", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            topic?.let { item { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) } }
            item { Text("Wähle einzelne Karten aus, die du wieder lernen möchtest. Die Inhalte bleiben unverändert.") }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Gelernte Karten durchsuchen") },
                    supportingText = { Text("Suche in Thema, Frage und Antwort") },
                    leadingIcon = { Icon(Icons.Default.Search, null) })
            }
            item { Text("$count gelernt · ${matches.size} angezeigt", style = MaterialTheme.typography.bodySmall) }
            if (count == 0) item { Text("Noch keine Karten als gelernt markiert.") }
            else if (matches.isEmpty()) item { Text("Keine passenden gelernten Karten gefunden.") }
            items(matches, key = { it.id }) { card ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.topic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(card.front, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = {
                            setLearned(card.id, false)
                            scope.launch {
                                val result = snackbar.showSnackbar("Karte wieder eingeblendet", actionLabel = "Rückgängig", duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed) setLearned(card.id, true)
                            }
                        }, modifier = Modifier.semantics { contentDescription = "Karte wieder lernen: ${card.front}" }) {
                            Text("Wieder lernen")
                        }
                    }
                }
            }
        }
    }
}

private val cardSessionSaver = Saver<CardSessionState, String>(
    save = { Json.encodeToString(it) },
    restore = { raw -> runCatching { Json.decodeFromString<CardSessionState>(raw) }.getOrNull() }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardSession(cards: List<PublishedCard>, random: Boolean, learned: Set<String>, showLearned: Boolean, setLearned: (String, Boolean) -> Unit, searchQuery: String = "", back: () -> Unit) {
    val fingerprint = remember(cards) { cardDeckFingerprint(cards) }
    var saved by rememberSaveable(stateSaver = cardSessionSaver) {
        mutableStateOf(CardSessionState(fingerprint, random, Random.Default.nextLong()))
    }
    val prepared = saved.forDeck(fingerprint, random)
    // A seeded order survives Activity recreation, and never depends on learned IDs.
    val order = remember(cards, prepared.shuffled, prepared.seed) { prepared.ordered(cards) }
    val session = remember(order, learned, showLearned) { CardVisibility.visible(order, learned, showLearned) }
    val current = prepared.forVisible(session)
    SideEffect { if (saved != current) saved = current }
    val index = session.indexOfFirst { it.id == current.cardId }
    val card = session.getOrNull(index)
    val flipped = current.showingAnswer
    Scaffold(topBar = { TopAppBar(title = { Text("Karteikarten") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        if (card == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (searchQuery.isNotBlank()) "Keine sichtbaren Karten für diese Suche mehr. Ändere die Suche oder blende gelernte Karten in der Übersicht wieder ein." else "Keine sichtbaren Karten mehr. Du kannst gelernte Karten in der Übersicht wieder einblenden.", textAlign = TextAlign.Center)
                OutlinedButton(onClick = back) { Text("Zurück") }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (searchQuery.isNotBlank()) Text("Gefilterte Karten · Suche: $searchQuery", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text("${index + 1} / ${session.size}" + if (card.id in learned) " · Gelernt" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { saved = current.copy(shuffled = true, seed = Random.Default.nextLong(), cardId = null, showingAnswer = false) }, enabled = session.size > 1) {
                    Text(if (current.shuffled) "Neu mischen" else "Karten mischen")
                }
                if (current.shuffled) TextButton(onClick = { saved = current.copy(shuffled = false, cardId = null, showingAnswer = false) }) { Text("Ursprüngliche Reihenfolge") }
            }
            ElevatedCard(onClick = { saved = current.copy(showingAnswer = !flipped) }, modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    key(card.id, flipped) {
                        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (flipped) "ANTWORT" else "FRAGE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(if (flipped) card.back else card.front, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Text("Zum Umdrehen tippen", style = MaterialTheme.typography.bodySmall)
                            if (flipped) Text("Quelle: ${card.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                val markLearned = card.id !in learned
                if (markLearned && !showLearned) saved = current.afterHide(session, index)
                setLearned(card.id, markLearned)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (card.id in learned) "Wieder lernen · einblenden" else if (showLearned) "Als gelernt markieren" else "Gelernt · Karte ausblenden")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { saved = current.select(session[index - 1]) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vorherige Karte") }
                IconButton(onClick = { saved = current.select(session[index + 1]) }, enabled = index < session.lastIndex) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Nächste Karte") }
            }
        }
    }
}

@Composable
internal fun CasePicker(catalog: LearningCatalog, completed: Set<String>, setCompleted: (String, Boolean) -> Unit, open: (String) -> Unit) {
    val cases = remember(catalog.cases) { catalog.cases.sortedBy { it.id } }
    val index = remember(cases) { CaseSearchIndex(cases) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = CaseSearchIndex.normalize(query).isNotEmpty()
    val results = remember(index, query) { index.search(query) }
    var expandedDays by rememberSaveable { mutableIntStateOf(0) }
    var collapsedSearchDays by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("caseList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Fallbeispiele", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Klappe einen Prüfungstag auf. Markierungen werden auf diesem Gerät gespeichert.") }
        item {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Fallbeispiele durchsuchen") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                supportingText = { Text("Suche auch in Aufgaben und ausklappbaren Antworten") },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Suche löschen") }
                })
        }
        if (cases.isEmpty()) item { Text("Noch keine Fallbeispiele veröffentlicht.") }
        else if (searching && results.isEmpty()) item { Text("Keine passenden Fallbeispiele gefunden.") }
        else if (searching) item { Text("${results.size} passende Fälle", style = MaterialTheme.typography.bodySmall) }
        listOf(1, 2, 3, 0).forEach { day ->
            val group = results.filter { result -> result.case.let { if (day == 0) it.examDay == null || it.examDay !in 1..3 else it.examDay == day } }
            if (group.isNotEmpty()) {
                val expanded = if (searching) collapsedSearchDays and (1 shl day) == 0 else expandedDays and (1 shl day) != 0
                item(key = "case-day-$day") {
                    DayHeader(if (day == 0) "Weitere Fälle" else "Prüfungstag $day",
                        "${group.size} ${if (searching) "passende Fälle" else "Fälle"} · ${group.count { it.case.id in completed }} bearbeitet", expanded) {
                        if (searching) collapsedSearchDays = collapsedSearchDays xor (1 shl day)
                        else expandedDays = expandedDays xor (1 shl day)
                    }
                }
                if (expanded) items(group, key = { it.case.id }) { result ->
                    val case = result.case
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().clickable { open(case.id) }.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(case.title.ifBlank { case.diseases.joinToString(" · ") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${case.questions.size} Aufgaben · Fall öffnen", style = MaterialTheme.typography.bodySmall)
                                if (searching) Text("Treffer: ${result.fields.joinToString(", ") { it.label }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
internal fun CaseDetail(case: PublishedCase, completed: Boolean, setCompleted: (Boolean) -> Unit, openDayTopics: (Int) -> Unit, back: () -> Unit) {
    val title = case.title.ifBlank { case.diseases.joinToString(" · ") }
    val sections = remember(case.text) { CasePresentation.sections(case.text) }
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 2) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("caseDetailList"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(case.examDay?.let { "FALLBEISPIEL · PRÜFUNGSTAG $it" } ?: "FALLBEISPIEL", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item { CaseCompletionToggle(completed, setCompleted) }
            case.examDay?.takeIf { it in 1..3 }?.let { day ->
                item {
                    OutlinedButton(onClick = { openDayTopics(day) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Lernzettel für Prüfungstag $day")
                    }
                }
            }
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
                var expanded by rememberSaveable(case.id, i) { mutableStateOf(false) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaseDayTopics(catalog: LearningCatalog, day: Int?, open: (Int, String) -> Unit, back: () -> Unit) {
    val indices = remember(day) { CasePresentation.topicIndicesForDay(day) }
    Scaffold(topBar = { TopAppBar(title = { Text("Lernzettel · Prüfungstag ${day ?: "–"}") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zum Fall") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Themen dieses Prüfungstags", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { Text("Die Liste zeigt alle festen Themen des Prüfungstags. Nicht jedes Thema muss zu diesem Fall passen.") }
            if (indices.isEmpty()) item { Text("Für diesen Prüfungstag sind keine Themen verfügbar.") }
            items(indices, key = { it }) { index ->
                val title = DefaultCatalogs.defaultPreparations[index].title
                val available = catalog.notes.any { it.topic == title && it.formatVersion == 2 }
                TopicTile(title, available, mainMatch = false, simpleMatch = false) { depth -> open(index, depth) }
            }
        }
    }
}

@Composable
internal fun QuizScreen(catalog: LearningCatalog, progress: QuizProgressStore, openArchive: (() -> Unit)? = null,
    modifier: Modifier = Modifier, openNote: (Int, String) -> Unit) {
    val dates = remember(catalog.quizzes) { catalog.quizzes.map { it.published }.distinct().sortedDescending() }
    val today by produceState(initialValue = berlinQuizDay()) {
        while (true) {
            delay(60_000)
            value = berlinQuizDay()
        }
    }
    var selectedDate by rememberSaveable(dates) { mutableStateOf(dates.firstOrNull()) }
    val quiz = remember(catalog.quizzes, selectedDate) { catalog.quizzes.filter { it.published == selectedDate }.sortedBy { it.id } }
    var session by remember(selectedDate, quiz, progress) { mutableStateOf(selectedDate?.let { progress.load(it, quiz) }) }
    var confirmRestart by remember(selectedDate, quiz) { mutableStateOf(false) }
    var reviewing by rememberSaveable(selectedDate, quiz) { mutableStateOf(false) }
    var onlyWrong by rememberSaveable(selectedDate, quiz) { mutableStateOf(false) }
    var reviewPosition by rememberSaveable(selectedDate, quiz) { mutableIntStateOf(0) }
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
                reviewing = false
                reviewPosition = 0
                scope.launch { scroll.scrollTo(0) }
            }) { Text("Neu beginnen") } },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Abbrechen") } })
    }
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Multiple-Choice-Quiz", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Vier Antwortmöglichkeiten · eine Antwort ist richtig", style = MaterialTheme.typography.bodyMedium)
        Text("Dein Übungsfortschritt wird nur auf diesem Gerät gespeichert und nicht für die Rangliste gewertet.", style = MaterialTheme.typography.bodySmall)
        if (openArchive != null) {
            Text(loadedQuizDayMessage(catalog.quizzes.map { it.published }, today), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = openArchive) { Text("Frühere Quiztage im Archiv") }
        }
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
        if (quiz.isEmpty() || current == null) Text("Im geladenen Inhaltsstand sind keine Quizfragen enthalten.")
        else if (current.index >= quiz.size) {
            Text("Übung beendet: ${current.correctCount(quiz)} von ${quiz.size} richtig.", style = MaterialTheme.typography.titleLarge)
            Text("Diese lokale Übung zählt nicht für die Rangliste.")
            if (!reviewing) {
                val wrongCount = current.reviewIndices(quiz, onlyWrong = true).size
                Button(onClick = { onlyWrong = false; reviewPosition = 0; reviewing = true; scope.launch { scroll.scrollTo(0) } }) {
                    Text("Alle Antworten ansehen")
                }
                if (wrongCount > 0) {
                    OutlinedButton(onClick = { onlyWrong = true; reviewPosition = 0; reviewing = true; scope.launch { scroll.scrollTo(0) } }) {
                        Text("$wrongCount falsch beantwortete Fragen ansehen")
                    }
                } else {
                    Text("Alle Fragen richtig beantwortet.")
                }
                OutlinedButton(onClick = { confirmRestart = true }) { Text("Erneut üben") }
                HorizontalDivider()
                Text("Ergebnis nach Thema", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Nur für diese Übung; das Ergebnis ist keine Bewertung deines gesamten Wissens.", style = MaterialTheme.typography.bodySmall)
                current.topicResults(quiz).forEach { result ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${result.correct} von ${result.total} richtig")
                            QuizTopicNoteLinks(catalog, result.topic, openNote)
                        }
                    }
                }
            } else {
                val indices = current.reviewIndices(quiz, onlyWrong)
                val questionIndex = indices.getOrNull(reviewPosition)
                if (questionIndex == null) {
                    // A restored UI position may no longer exist after content or progress changes.
                    Text("Diese Durchsicht ist nicht mehr verfügbar.")
                } else {
                    val q = quiz[questionIndex]
                    val selected = current.answers[questionIndex]
                    Text(if (onlyWrong) "Falsch beantwortete Fragen" else "Alle Antworten", style = MaterialTheme.typography.titleMedium)
                    Text("${reviewPosition + 1} / ${indices.size} · Frage ${questionIndex + 1} · ${q.topic}", style = MaterialTheme.typography.labelLarge)
                    Text(q.question, style = MaterialTheme.typography.titleLarge)
                    Text("Deine Antwort: ${q.options[selected]}")
                    Text(if (selected == q.correctIndex) "Richtig" else "Richtige Antwort: ${q.options[q.correctIndex]}", fontWeight = FontWeight.Bold)
                    Text(q.explanation)
                    Text("Quelle: ${q.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                    QuizTopicNoteLinks(catalog, q.topic, openNote)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { reviewPosition--; scope.launch { scroll.scrollTo(0) } }, enabled = reviewPosition > 0) { Text("Zurück") }
                        OutlinedButton(onClick = { reviewPosition++; scope.launch { scroll.scrollTo(0) } }, enabled = reviewPosition < indices.lastIndex) { Text("Weiter") }
                    }
                }
                TextButton(onClick = { reviewing = false; scope.launch { scroll.scrollTo(0) } }) { Text("Zur Ergebnisübersicht") }
            }
        } else {
            val q = quiz[current.index]
            val choice = current.answers.getOrNull(current.index)
            Text("${current.index + 1} / ${quiz.size} · ${q.topic}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(q.question, style = MaterialTheme.typography.titleLarge)
            q.options.forEachIndexed { option, text ->
                OutlinedButton(onClick = { update(current.answer(option, quiz)) }, enabled = choice == null, modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.fillMaxWidth()) }
            }
            if (choice != null) {
                Text("Deine Antwort: ${q.options[choice]}")
                Text(if (choice == q.correctIndex) "Richtig" else "Nicht richtig. Richtig ist: ${q.options[q.correctIndex]}", fontWeight = FontWeight.Bold)
                Text(q.explanation); Text("Quelle: ${q.sources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { update(current.advance(quiz)); scope.launch { scroll.scrollTo(0) } }) { Text("Nächste Frage") }
            }
            if (current.answers.isNotEmpty()) OutlinedButton(onClick = { confirmRestart = true }) { Text("Quiz neu beginnen") }
        }
    }
}

/** Link only to an existing fixed topic with its bundled/published two-version note. */
@Composable
private fun QuizTopicNoteLinks(catalog: LearningCatalog, topic: String, openNote: (Int, String) -> Unit) {
    val index = remember(topic) { DefaultCatalogs.defaultPreparations.indexOfFirst { it.title == topic } }
    val available = catalog.notes.any { it.topic == topic && it.formatVersion == 2 }
    if (index >= 0 && available) {
        TextButton(onClick = { openNote(index, "detailed") }) { Text("Lernzettel öffnen") }
        TextButton(onClick = { openNote(index, "simple") }) { Text("Vereinfachten Lernzettel öffnen") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuizRankingScreen(openParticipation: () -> Unit, back: () -> Unit) {
    val client = remember { QuizRankingClient(BuildConfig.RANKING_URL, HttpsRankingTransport()) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<RankingLoad?>(null) }
    suspend fun reload() {
        loading = true
        try { result = client.load() } finally { loading = false }
    }
    LaunchedEffect(client) { reload() }
    Scaffold(topBar = { TopAppBar(title = { Text("Quiz-Rangliste") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Quiz-Rangliste", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            item { Text("Nur gewertete Multiple-Choice-Quiz zählen. Deine lokale Übung wird nicht an eine Rangliste übertragen.") }
            if (BuildConfig.RANKING_URL.isNotBlank()) item { RankingFairnessNotice() }
            if (BuildConfig.ENABLE_RANKED_PARTICIPATION && BuildConfig.RANKING_URL.isNotBlank()) item {
                Button(onClick = openParticipation) { Text("Am Wertungsquiz teilnehmen") }
            }
            if (BuildConfig.RANKING_URL.isNotBlank()) item { PendingQuizRecovery(BuildConfig.RANKING_URL) }
            if (loading) item { Text("Rangliste wird geladen …") }
            else when (val state = result) {
                RankingLoad.NotConfigured -> item { Text("Die Rangliste ist noch nicht eingerichtet.") }
                RankingLoad.Unavailable, null -> item { Text("Die Rangliste ist derzeit nicht verfügbar. Bitte später erneut versuchen.") }
                is RankingLoad.Ready -> {
                    val snapshot = state.snapshot
                    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(snapshot.generatedAtMillis))
                    item { Text("Stand: $time · ${snapshot.totalParticipants} Teilnehmende", style = MaterialTheme.typography.bodyMedium) }
                    item { Text("Der Server aktualisiert die Rangliste ungefähr stündlich; der angezeigte Stand kann älter sein.", style = MaterialTheme.typography.bodySmall) }
                    if (snapshot.entries.isEmpty()) item { Text("Noch keine gewerteten Quizversuche.") }
                    items(snapshot.entries, key = { it.rank }) { entry ->
                        ListItem(headlineContent = { Text("${entry.rank}. ${entry.username}") },
                            supportingContent = { Text("${entry.quizzes} Quiz") }, trailingContent = { Text("${entry.points} Punkte") })
                    }
                }
            }
            if (!loading && result != RankingLoad.NotConfigured) item {
                OutlinedButton(onClick = { loading = true; scope.launch { reload() } }) { Text("Rangliste aktualisieren") }
            }
        }
    }
}

@Composable
internal fun RankingFairnessNotice() {
    Text("Die Rangliste ist nicht manipulationssicher: Lösungen sind sichtbar und mehrere Konten möglich. Sie ist keine faire Prüfung.", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettings(store: PublishedContentStore, back: () -> Unit) {
    val context = LocalContext.current
    val catalog by store.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ContentRefreshResult?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Lerninhalte", style = MaterialTheme.typography.titleLarge)
            Text("Inhaltsstand: ${catalog.revision}. ${if (PublishedContentStore.CONTENT_URL.isBlank()) "Automatische Updates sind noch nicht eingerichtet." else "Updates werden bei verfügbarer Verbindung regelmäßig geprüft."}")
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                result = null
                scope.launch {
                    try { result = store.refresh() } finally { checking = false }
                }
            }) { Text(if (checking) "Prüfung läuft …" else "Jetzt auf Updates prüfen") }
            result?.let { status ->
                Text(when (status) {
                    ContentRefreshResult.UPDATED -> "Neue Inhalte wurden heruntergeladen."
                    ContentRefreshResult.UP_TO_DATE -> "Der abgerufene Stand entspricht dem lokalen Inhaltsstand."
                    ContentRefreshResult.NOT_CONFIGURED -> "Für diese App-Version ist keine Update-Adresse eingerichtet. Der Offline-Stand bleibt verfügbar."
                    ContentRefreshResult.SERVER_OLDER -> "Der abgerufene Stand ist älter als der lokale Stand. Der Offline-Stand bleibt erhalten."
                    ContentRefreshResult.INVALID_OR_INCOMPLETE -> "Der Serverstand ist ungültig, unvollständig oder widersprüchlich. Der Offline-Stand bleibt erhalten."
                    ContentRefreshResult.UNAVAILABLE -> "Die Prüfung oder das Speichern war nicht möglich. Der Offline-Stand bleibt erhalten. Bitte später erneut versuchen."
                }, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Text("Darstellung & Bedienung", style = MaterialTheme.typography.titleLarge)
            Text("Schriftgröße, Kontrast und weitere Bedienhilfen kannst du in den Android-Einstellungen ändern.")
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Android-Bedienhilfen öffnen") }
            HorizontalDivider()
            Text("Datenschutz", style = MaterialTheme.typography.titleLarge)
            Text("Die Rangliste ist freiwillig und nutzt einen separaten Zugang. Lokale Übungsfortschritte und frühere Lernpunkte werden nicht automatisch übertragen.")
            ProgressBackupNotice()
        }
    }
}

@Composable
internal fun ProgressBackupNotice() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lernfortschritt sichern", style = MaterialTheme.typography.titleLarge)
        Text("Ein vollständiger manueller Export des Lernfortschritts ist noch nicht verfügbar. Android-Backup oder Geräteübertragung sind nicht garantiert und wurden nicht vollständig auf echten Geräten wiederhergestellt.")
        Text("App nicht deinstallieren und App-Daten nicht löschen, solange keine Wiederherstellung geprüft ist. Bei Geräte- oder Signaturwechsel können lokale Daten verloren gehen.")
    }
}
