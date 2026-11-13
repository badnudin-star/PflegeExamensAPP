package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.LeaderboardEntry
import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.RankingSettings
import kotlin.math.ceil

/**
 * Reine Kotlin-Logik für Benutzernamen-Validierung, Ranking-Zusammenführung
 * und Berechnung des stündlichen Update-Countdowns (KMP-kompatibel).
 */
object RankingEngine {

    private val allowedCharsRegex = Regex("""[^\p{L}\p{N}_\- .]""")

    fun sanitizeUsername(raw: String): String {
        return raw
            .replace(allowedCharsRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(24)
    }

    fun validateUsername(raw: String): String? {
        val trimmed = raw.trim()
        if (allowedCharsRegex.containsMatchIn(trimmed)) {
            return "Bitte nur Buchstaben, Zahlen, Leerzeichen, _, - oder . nutzen."
        }
        val clean = sanitizeUsername(trimmed)
        return when {
            clean.length < 2 -> "Bitte mindestens 2 Zeichen eingeben."
            clean.length > 24 -> "Maximal 24 Zeichen erlaubt."
            else -> null
        }
    }

    fun isHourlySyncDue(
        lastSyncMillis: Long,
        snapshot: LeaderboardSnapshot?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (lastSyncMillis <= 0L) return true
        if (snapshot != null && snapshot.nextUpdateAtMillis > 0L && nowMillis >= snapshot.nextUpdateAtMillis) {
            return true
        }
        return (nowMillis - lastSyncMillis) >= 60L * 60L * 1000L
    }

    fun minutesUntilNextUpdate(
        snapshot: LeaderboardSnapshot?,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        val target = snapshot?.nextUpdateAtMillis ?: nextFullHourMillis(nowMillis)
        val diff = (target - nowMillis).coerceAtLeast(0L)
        return ceil(diff / 60_000.0).toInt().coerceIn(0, 60)
    }

    fun nextFullHourMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val hourMs = 60L * 60L * 1000L
        return ((nowMillis / hourMs) + 1L) * hourMs
    }

    /**
     * Verbindet den letzten stündlichen Server-/Cloudflare-Snapshot mit dem lokalen Stand des Nutzers,
     * sodass die eigene Zeile immer eindeutig markiert ist und im Offline-/Vorschau-Modus ein sauberer
     * lokaler Eintrag angezeigt wird.
     */
    fun buildDisplaySnapshot(
        cachedSnapshot: LeaderboardSnapshot?,
        settings: RankingSettings,
        localXp: Int,
        localStreakDays: Int,
        localRankTitle: String,
        nowMillis: Long = System.currentTimeMillis()
    ): LeaderboardSnapshot {
        val displayName = settings.username.trim().ifBlank { "Du (lokal)" }
        if (cachedSnapshot == null || cachedSnapshot.entries.isEmpty()) {
            val localOnly = LeaderboardEntry(
                rank = 1,
                userId = settings.userId,
                username = displayName,
                totalXp = localXp,
                streakDays = localStreakDays,
                rankTitle = localRankTitle,
                updatedAtMillis = nowMillis,
                isCurrentUser = true
            )
            return LeaderboardSnapshot(
                generatedAtMillis = nowMillis,
                nextUpdateAtMillis = nextFullHourMillis(nowMillis),
                totalParticipants = 1,
                entries = listOf(localOnly),
                sourceLabel = if (settings.isEndpointConfigured()) {
                    "Warte auf erstes stündliches Ranking-Update"
                } else {
                    "Lokale Vorschau (Endpunkt noch nicht eingerichtet)"
                }
            )
        }

        val mapped = cachedSnapshot.entries.map { entry ->
            if (entry.userId == settings.userId) {
                entry.copy(
                    username = displayName,
                    isCurrentUser = true
                )
            } else {
                entry.copy(isCurrentUser = false)
            }
        }
        val hasCurrent = mapped.any { it.isCurrentUser }
        val finalEntries = if (hasCurrent || !settings.hasUsername()) {
            mapped
        } else {
            val combined = mapped + LeaderboardEntry(
                rank = 0,
                userId = settings.userId,
                username = displayName,
                totalXp = localXp,
                streakDays = localStreakDays,
                rankTitle = localRankTitle,
                updatedAtMillis = nowMillis,
                isCurrentUser = true
            )
            combined
                .sortedWith(compareByDescending<LeaderboardEntry> { it.totalXp }.thenByDescending { it.streakDays })
                .mapIndexed { idx, e -> e.copy(rank = idx + 1) }
        }

        return cachedSnapshot.copy(
            totalParticipants = maxOf(cachedSnapshot.totalParticipants, finalEntries.size),
            entries = finalEntries
        )
    }
}
