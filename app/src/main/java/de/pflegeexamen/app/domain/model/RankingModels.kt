package de.pflegeexamen.app.domain.model

import kotlinx.serialization.Serializable

data class RankingSettings(
    val userId: String = "",
    val username: String = "",
    val rankingOptIn: Boolean = true,
    val endpointUrl: String = DEFAULT_RANKING_ENDPOINT_URL,
    val lastSyncMillis: Long = 0L
) {
    fun hasUsername(): Boolean = username.trim().length >= 2

    fun normalizedEndpoint(): String = endpointUrl.trim().trimEnd('/')

    fun isEndpointConfigured(): Boolean {
        val url = normalizedEndpoint().lowercase()
        return url.startsWith("https://") || url.startsWith("http://")
    }

    companion object {
        /**
         * Optional hier oder über seed_bundle/manifest.json (bzw. in den Einstellungen)
         * auf die eigene kostenlose Cloudflare-Worker-URL setzen.
         */
        const val DEFAULT_RANKING_ENDPOINT_URL = ""
    }
}

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val userId: String,
    val username: String,
    val totalXp: Int,
    val streakDays: Int,
    val rankTitle: String,
    val updatedAtMillis: Long = 0L,
    val isCurrentUser: Boolean = false
)

@Serializable
data class LeaderboardSnapshot(
    val generatedAtMillis: Long,
    val nextUpdateAtMillis: Long,
    val totalParticipants: Int,
    val entries: List<LeaderboardEntry>,
    val sourceLabel: String = "Stündliches Ranking"
)

@Serializable
data class ScoreSubmissionPayload(
    val userId: String,
    val username: String,
    val totalXp: Int,
    val streakDays: Int,
    val rankTitle: String
)
