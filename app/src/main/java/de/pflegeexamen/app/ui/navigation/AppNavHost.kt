package de.pflegeexamen.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.ui.screens.casestudies.CaseStudiesScreen
import de.pflegeexamen.app.ui.screens.casestudies.CaseStudyPracticeScreen
import de.pflegeexamen.app.ui.screens.home.HomeScreen
import de.pflegeexamen.app.ui.screens.operators.OperatorsScreen
import de.pflegeexamen.app.ui.screens.preparation.PreparationScreen
import de.pflegeexamen.app.ui.screens.ranking.RankingScreen
import de.pflegeexamen.app.ui.screens.settings.SettingsScreen
import de.pflegeexamen.app.ui.screens.sources.SourcesScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "Start", Icons.Outlined.Home),
    SOURCES("sources", "Quellen", Icons.Outlined.FolderCopy),
    PREPARATION("preparation", "Lernen", Icons.AutoMirrored.Outlined.ListAlt),
    OPERATORS("operators", "Operatoren", Icons.Outlined.Quiz),
    CASE_STUDIES("case_studies", "Fälle", Icons.AutoMirrored.Outlined.Assignment)
}

@Composable
fun PflegeExamenNavHost(repository: PflegeRepository) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val rankingSettings by repository.rankingSettings.collectAsStateWithLifecycle()
    var prepSubTab by remember { mutableIntStateOf(0) }

    // Stündlicher Foreground-Check (ergänzt den nativen JobScheduler alle 60 Min.)
    LaunchedEffect(rankingSettings.endpointUrl, rankingSettings.username, rankingSettings.rankingOptIn) {
        while (true) {
            if (rankingSettings.hasUsername() && rankingSettings.isEndpointConfigured()) {
                repository.syncRanking(forceEvenIfNotDue = false)
            }
            delay(5L * 60L * 1000L)
        }
    }

    val showBottomBar = TopDestination.entries.any { it.route == currentRoute }

    fun navigateTopLevel(dest: TopDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigateTopLevel(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopDestination.HOME.route) {
                HomeScreen(
                    repository = repository,
                    onNavigateToSources = { navigateTopLevel(TopDestination.SOURCES) },
                    onNavigateToPreparation = {
                        prepSubTab = 0
                        navigateTopLevel(TopDestination.PREPARATION)
                    },
                    onNavigateToFlashcards = {
                        prepSubTab = 2
                        navigateTopLevel(TopDestination.PREPARATION)
                    },
                    onNavigateToOperators = { navigateTopLevel(TopDestination.OPERATORS) },
                    onNavigateToCaseStudies = { navigateTopLevel(TopDestination.CASE_STUDIES) },
                    onNavigateToRanking = { navController.navigate("ranking") },
                    onNavigateToSettings = { navController.navigate("settings") }
                )
            }

            composable(TopDestination.SOURCES.route) {
                SourcesScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState
                )
            }

            composable(TopDestination.PREPARATION.route) {
                PreparationScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    initialSubTab = prepSubTab
                )
            }

            composable(TopDestination.OPERATORS.route) {
                OperatorsScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState
                )
            }

            composable(TopDestination.CASE_STUDIES.route) {
                CaseStudiesScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    onOpenCaseStudy = { caseId ->
                        navController.navigate("case_study/$caseId")
                    },
                    onNavigateToSources = { navigateTopLevel(TopDestination.SOURCES) },
                    onNavigateToOperators = { navigateTopLevel(TopDestination.OPERATORS) }
                )
            }

            composable(
                route = "case_study/{caseId}",
                arguments = listOf(navArgument("caseId") { type = NavType.LongType })
            ) { entry ->
                val caseId = entry.arguments?.getLong("caseId") ?: return@composable
                CaseStudyPracticeScreen(
                    caseStudyId = caseId,
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("ranking") {
                RankingScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }

            composable("settings") {
                SettingsScreen(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (!rankingSettings.hasUsername()) {
        UsernameOnboardingDialog(
            onConfirm = { cleanName ->
                scope.launch {
                    repository.saveUsernameAndSync(cleanName)
                    snackbarHostState.showSnackbar("Willkommen, $cleanName!")
                }
            }
        )
    }
}

@Composable
private fun UsernameOnboardingDialog(
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val errorMsg = RankingEngine.validateUsername(input)

    AlertDialog(
        onDismissRequest = { /* Pflichteingabe beim ersten Öffnen */ },
        icon = { Icon(Icons.Outlined.EmojiEvents, contentDescription = null) },
        title = { Text("Benutzername wählen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Bitte gib einen Benutzernamen (Pseudonym) ein, mit dem du im stündlichen Lern-Ranking erscheinst.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(24) },
                    label = { Text("Benutzername * (z. B. PflegeFuchs27)") },
                    singleLine = true,
                    isError = input.isNotEmpty() && errorMsg != null,
                    supportingText = {
                        if (input.isNotEmpty() && errorMsg != null) {
                            Text(errorMsg)
                        } else {
                            Text("2–24 Zeichen · jederzeit in den Einstellungen änderbar")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Datenschutz: Bitte keinen echten vollen Namen wählen. Es werden nur Benutzername, XP, Streak und Rang übertragen – niemals deine Quellen, Lernzettel oder Fallantworten.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = RankingEngine.sanitizeUsername(input)
                    if (RankingEngine.validateUsername(clean) == null && !isSaving) {
                        isSaving = true
                        onConfirm(clean)
                    }
                },
                enabled = errorMsg == null && !isSaving
            ) {
                Text("Starten")
            }
        }
    )
}
