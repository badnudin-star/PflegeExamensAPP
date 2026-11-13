package de.pflegeexamen.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.local.StudyProfileEntity
import de.pflegeexamen.app.data.repository.PflegeRepository
import de.pflegeexamen.app.domain.engine.GamificationEngine
import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.domain.model.ExamRank
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.ui.components.OfflineSourceBanner
import de.pflegeexamen.app.ui.theme.SuccessGreen
import de.pflegeexamen.app.ui.theme.WarningOrange

private data class HomeGamificationUi(
    val rank: ExamRank,
    val nextRankTitle: String?,
    val xpToNextRank: Int,
    val rankProgress: Float,
    val totalXp: Int,
    val streakDays: Int,
    val freeDaysRemaining: Int,
    val todayPoints: Int,
    val dailyGoalPoints: Int,
    val dailyGoalPercent: Int,
    val examReadinessPercent: Int,
    val weakTopics: List<Pair<String, Int>>
)

@Composable
fun HomeScreen(
    repository: PflegeRepository,
    onNavigateToSources: () -> Unit,
    onNavigateToPreparation: () -> Unit,
    onNavigateToFlashcards: () -> Unit,
    onNavigateToOperators: () -> Unit,
    onNavigateToCaseStudies: () -> Unit,
    onNavigateToRanking: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val aiSettings by repository.aiSettings.collectAsStateWithLifecycle()
    val rankingSettings by repository.rankingSettings.collectAsStateWithLifecycle()
    val cachedLeaderboard by repository.cachedLeaderboard.collectAsStateWithLifecycle()
    val profile by repository.studyProfile.collectAsStateWithLifecycle(initialValue = StudyProfileEntity())
    val sources by repository.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val prepItems by repository.preparationItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val operators by repository.operators.collectAsStateWithLifecycle(initialValue = emptyList())
    val studyNotes by repository.studyNotes.collectAsStateWithLifecycle(initialValue = emptyList())
    val flashcards by repository.flashcards.collectAsStateWithLifecycle(initialValue = emptyList())
    val dueCount by repository.observeDueFlashcardCount().collectAsStateWithLifecycle(initialValue = 0)
    val caseStudies by repository.caseStudies.collectAsStateWithLifecycle(initialValue = emptyList())

    val gamification = remember(profile, studyNotes, flashcards, caseStudies) {
        val currentProfile = profile ?: StudyProfileEntity()
        val allQuestions = caseStudies.flatMap { it.questions }
        val masteredNotes = studyNotes.count { it.masteryStatus == MasteryStatus.MASTERED }
        val reviewingNotes = studyNotes.count { it.masteryStatus == MasteryStatus.REVIEWING }
        val maturedCards = flashcards.count { it.repetitions >= 2 }
        val confirmedQuestions = allQuestions.count { it.isAnswerConfirmed }

        val radarEntries = mutableListOf<Pair<String, Int>>()
        for (note in studyNotes) {
            val label = note.categoryLabel.ifBlank { note.title }
            val score = when (note.masteryStatus) {
                MasteryStatus.MASTERED -> 95
                MasteryStatus.REVIEWING -> 60
                MasteryStatus.OPEN -> 25
            }
            radarEntries.add(label to score)
        }
        for (q in allQuestions) {
            if (q.isAnswerConfirmed && q.totalKeyPointsCount > 0) {
                val pct = ((q.matchedKeyPointsCount.toFloat() / q.totalKeyPointsCount) * 100f).toInt()
                radarEntries.add(q.operatorName to pct)
            }
        }
        val weakTopics = radarEntries
            .groupBy { it.first }
            .map { (label, pairs) -> label to pairs.map { it.second }.average().toInt() }
            .filter { it.second < 75 }
            .sortedBy { it.second }
            .take(3)

        val readiness = GamificationEngine.calculateReadinessPercent(
            totalNotes = studyNotes.size,
            masteredNotes = masteredNotes,
            reviewingNotes = reviewingNotes,
            totalCards = flashcards.size,
            matureOrReviewedCards = maturedCards,
            totalCaseQuestions = allQuestions.size,
            confirmedCaseQuestions = confirmedQuestions
        )

        val rank = ExamRank.forXp(currentProfile.totalXp)
        val nextRank = ExamRank.entries.getOrNull(rank.ordinal + 1)
        val nextMin = rank.nextMinXp
        val rankProgress = if (nextMin == null) {
            1f
        } else {
            val span = (nextMin - rank.minXp).coerceAtLeast(1)
            ((currentProfile.totalXp - rank.minXp).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }
        val xpToNext = nextMin?.let { (it - currentProfile.totalXp).coerceAtLeast(0) } ?: 0

        val todayEpoch = GamificationEngine.epochDayOf(System.currentTimeMillis())
        val todayPts = if (currentProfile.lastActiveDayEpoch == todayEpoch) currentProfile.todayPoints else 0
        val goalPts = currentProfile.dailyGoalPoints.coerceAtLeast(1)
        val goalPct = ((todayPts.toFloat() / goalPts.toFloat()) * 100f).toInt().coerceIn(0, 100)

        HomeGamificationUi(
            rank = rank,
            nextRankTitle = nextRank?.title,
            xpToNextRank = xpToNext,
            rankProgress = rankProgress,
            totalXp = currentProfile.totalXp,
            streakDays = currentProfile.streakDays,
            freeDaysRemaining = currentProfile.freeDaysRemaining,
            todayPoints = todayPts,
            dailyGoalPoints = goalPts,
            dailyGoalPercent = goalPct,
            examReadinessPercent = readiness,
            weakTopics = weakTopics
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (rankingSettings.hasUsername()) {
                            "PflegeExamen · Hallo ${rankingSettings.username}"
                        } else {
                            "PflegeExamen Lokal"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Schriftliche Examensvorbereitung – kostenlos, quellenbasiert & schichtfreundlich.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Einstellungen & KI-Modus",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            OfflineSourceBanner(
                aiSettings = aiSettings,
                onClickSettings = onNavigateToSettings
            )
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MilitaryTech,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Stufe ${gamification.rank.ordinal + 1} · ${gamification.rank.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${gamification.totalXp} XP" +
                                        (gamification.nextRankTitle?.let { " · noch ${gamification.xpToNextRank} XP bis $it" }
                                            ?: " · Höchster Rang!"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Reife: ${gamification.examReadinessPercent}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { gamification.rankProgress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocalFireDepartment,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${gamification.streakDays} Tage Streak",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            repeat(gamification.freeDaysRemaining) {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = "Frei-Tag Schichtschutz",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "(${gamification.freeDaysRemaining} Frei-Tage)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Heute: ${gamification.todayPoints}/${gamification.dailyGoalPoints} P.",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (gamification.dailyGoalPercent >= 100) SuccessGreen else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { gamification.dailyGoalPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            val currentProfile = profile ?: StudyProfileEntity()
            val displayBoard = remember(cachedLeaderboard, rankingSettings, currentProfile) {
                RankingEngine.buildDisplaySnapshot(
                    cachedSnapshot = cachedLeaderboard,
                    settings = rankingSettings,
                    localXp = currentProfile.totalXp,
                    localStreakDays = currentProfile.streakDays,
                    localRankTitle = gamification.rank.title
                )
            }
            val myPos = displayBoard.entries.firstOrNull { it.isCurrentUser }
            val minLeft = RankingEngine.minutesUntilNextUpdate(displayBoard)

            ElevatedCard(
                onClick = onNavigateToRanking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = WarningOrange)
                            Column {
                                Text("Stündliches Community-Ranking", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = myPos?.let {
                                        "Dein Platz: #${it.rank} von ${displayBoard.totalParticipants} · Update in ~$minLeft Min."
                                    } ?: "Update in ~$minLeft Min.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        OutlinedButton(onClick = onNavigateToRanking) {
                            Text("Rangliste")
                        }
                    }

                    displayBoard.entries.take(3).forEach { entry ->
                        val medal = when (entry.rank) {
                            1 -> "🥇"
                            2 -> "🥈"
                            3 -> "🥉"
                            else -> "#${entry.rank}"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$medal ${entry.username}" + if (entry.isCurrentUser) " (Du)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "${entry.totalXp} XP",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (gamification.weakTopics.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = WarningOrange.copy(alpha = 0.12f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Schwachstellen-Radar",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        gamification.weakTopics.forEach { (label, pct) ->
                            Text(
                                text = "• $label (aktuell ~$pct % Sicherheit)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    title = "Quellen",
                    value = sources.size.toString(),
                    icon = Icons.Outlined.FolderCopy,
                    onClick = onNavigateToSources,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Themenliste",
                    value = prepItems.size.toString(),
                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                    onClick = onNavigateToPreparation,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Operatoren",
                    value = operators.size.toString(),
                    icon = Icons.Outlined.Quiz,
                    onClick = onNavigateToOperators,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    title = "Lernzettel",
                    value = studyNotes.size.toString(),
                    icon = Icons.Outlined.Description,
                    onClick = onNavigateToPreparation,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Fällige Karten",
                    value = "$dueCount / ${flashcards.size}",
                    icon = Icons.Outlined.Style,
                    onClick = onNavigateToFlashcards,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Fallbeispiele",
                    value = caseStudies.size.toString(),
                    icon = Icons.AutoMirrored.Outlined.Assignment,
                    onClick = onNavigateToCaseStudies,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Dein Arbeitsablauf (Eigene Datenbasis)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    WorkflowStepRow(
                        stepNumber = 1,
                        title = "Eigene Quellen importieren",
                        subtitle = "PDF, Word (.docx), Text/Markdown oder Buchseiten per Foto-OCR",
                        done = sources.isNotEmpty(),
                        actionLabel = if (sources.isEmpty()) "Hinzufügen" else "Verwalten",
                        onClick = onNavigateToSources
                    )

                    WorkflowStepRow(
                        stepNumber = 2,
                        title = "Vorbereitungsliste Frühjahr 2027 (Tag 1–3)",
                        subtitle = "42 Themen & Kompetenzschwerpunkte inkl. Anatomie/Physiologie – frei bearbeitbar",
                        done = prepItems.isNotEmpty(),
                        actionLabel = if (prepItems.isEmpty()) "Anlegen" else "Öffnen",
                        onClick = onNavigateToPreparation
                    )

                    WorkflowStepRow(
                        stepNumber = 3,
                        title = "Operatoren (Landes-AG / Dez. 2025)",
                        subtitle = "17 Examens-Operatoren (WV & ASB) inkl. Erwartungshorizont & Bepunktung",
                        done = operators.isNotEmpty(),
                        actionLabel = "Öffnen",
                        onClick = onNavigateToOperators
                    )

                    WorkflowStepRow(
                        stepNumber = 4,
                        title = "Lernzettel, Karteikarten & Fallbeispiele",
                        subtitle = "Offline oder per Gratis-KI aus deinen Quellen; Antwort nach Bestätigung",
                        done = studyNotes.isNotEmpty() || caseStudies.isNotEmpty(),
                        actionLabel = "Fallbeispiele",
                        onClick = onNavigateToCaseStudies
                    )
                }
            }
        }

        if (dueCount > 0 || (sources.isNotEmpty() && operators.isNotEmpty())) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Schnellstart Prüfungstraining",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (dueCount > 0) {
                                Button(
                                    onClick = onNavigateToFlashcards,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("$dueCount Karten üben")
                                }
                            }
                            if (sources.isNotEmpty() && operators.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = onNavigateToCaseStudies,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Fallbeispiel starten")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkflowStepRow(
    stepNumber: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$stepNumber. $title",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}
