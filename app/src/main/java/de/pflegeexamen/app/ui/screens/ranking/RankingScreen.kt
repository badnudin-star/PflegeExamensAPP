package de.pflegeexamen.app.ui.screens.ranking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.StudyProfileEntity
import de.pflegeexamen.app.data.ranking.RankingSyncResult
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.domain.model.ExamRank
import de.pflegeexamen.app.domain.model.LeaderboardEntry
import de.pflegeexamen.app.ui.theme.WarningOrange
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen(
    repository: PflegeRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val rankingSettings by repository.rankingSettings.collectAsStateWithLifecycle()
    val cachedSnapshot by repository.cachedLeaderboard.collectAsStateWithLifecycle()
    val profile by repository.studyProfile.collectAsStateWithLifecycle(initialValue = StudyProfileEntity())

    var isSyncing by remember { mutableStateOf(false) }

    val currentProfile = profile ?: StudyProfileEntity()
    val displaySnapshot = remember(cachedSnapshot, rankingSettings, currentProfile) {
        RankingEngine.buildDisplaySnapshot(
            cachedSnapshot = cachedSnapshot,
            settings = rankingSettings,
            localXp = currentProfile.totalXp,
            localStreakDays = currentProfile.streakDays,
            localRankTitle = ExamRank.forXp(currentProfile.totalXp).title
        )
    }
    val minutesToNext = remember(displaySnapshot) {
        RankingEngine.minutesUntilNextUpdate(displaySnapshot)
    }
    val myEntry = displaySnapshot.entries.firstOrNull { it.isCurrentUser }
    val timeFormatter = remember { SimpleDateFormat("HH:mm 'Uhr'", Locale.GERMANY) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stündliches Ranking") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                val res = repository.syncRanking(forceEvenIfNotDue = true)
                                isSyncing = false
                                when (res) {
                                    is RankingSyncResult.Success -> snackbarHostState.showSnackbar(res.statusMessage)
                                    is RankingSyncResult.NotConfigured -> snackbarHostState.showSnackbar(
                                        "Bitte zuerst deine kostenlose Ranking-URL in den Einstellungen hinterlegen."
                                    )
                                    is RankingSyncResult.Failure -> snackbarHostState.showSnackbar("Abgleich fehlgeschlagen: ${res.reason}")
                                }
                            }
                        },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Jetzt aktualisieren")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ranking-Einstellungen")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
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
                                    text = rankingSettings.username.ifBlank { "Noch kein Name" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = myEntry?.let {
                                        "Platz #${it.rank} von ${displaySnapshot.totalParticipants} · ${it.totalXp} XP"
                                    } ?: "Aktuelle XP: ${currentProfile.totalXp}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.EmojiEvents,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val updatedStr = timeFormatter.format(Date(displaySnapshot.generatedAtMillis))
                            Text(
                                text = "Stand: $updatedStr · Nächstes stündliches Update in ~$minutesToNext Min.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!rankingSettings.isEndpointConfigured()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Kostenlosen Ranking-Endpunkt aktivieren (0 €, ohne Server)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Im Projektordner „cloudflare-ranking/“ liegt der fertige, dauerhaft kostenlose Cloudflare-Worker (inkl. stündlichem Cron & optionalem GitHub-Export). Sobald du deine Worker-URL in den Einstellungen einträgst, synchronisiert sich die App automatisch jede Stunde mit allen Nutzern.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(onClick = onOpenSettings) {
                                Text("Endpunkt in Einstellungen eintragen")
                            }
                        }
                    }
                }
            }

            items(displaySnapshot.entries, key = { "${it.rank}_${it.userId}" }) { entry ->
                LeaderboardRowCard(entry = entry)
            }
        }
    }
}

@Composable
private fun LeaderboardRowCard(entry: LeaderboardEntry) {
    val containerColor = when {
        entry.isCurrentUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        entry.rank == 1 -> WarningOrange.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val rankText = when (entry.rank) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "#${entry.rank}"
            }
            Text(
                text = rankText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.username,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.SemiBold
                    )
                    if (entry.isCurrentUser) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "DU",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = entry.rankTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${entry.streakDays}d",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.size(2.dp))

            Text(
                text = "${entry.totalXp} XP",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
