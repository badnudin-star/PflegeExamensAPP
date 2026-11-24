package de.pflegeexamen.app.data.ranking

import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.RankingSettings
import de.pflegeexamen.app.domain.model.ScoreSubmissionPayload

sealed interface RankingSyncResult {
    data class Success(
        val snapshot: LeaderboardSnapshot,
        val statusMessage: String
    ) : RankingSyncResult

    data object NotConfigured : RankingSyncResult

    data class Failure(val reason: String) : RankingSyncResult
}

/** Shared explanation for the dormant XP-ranking UI and repository. */
const val LEGACY_RANKING_DISABLED_MESSAGE = "Die frühere XP-Rangliste ist abgeschaltet."

/**
 * Legacy XP/streak client retained only for compatibility with the dormant UI.
 * It must never send data, even with an old saved endpoint or opt-in. The separate
 * quiz-ranking clients use different protocols and are not enabled by this class.
 */
@Suppress("UNUSED_PARAMETER")
class HttpRankingClient {
    suspend fun syncWithEndpoint(
        settings: RankingSettings,
        payload: ScoreSubmissionPayload
    ): RankingSyncResult = RankingSyncResult.Failure(LEGACY_RANKING_DISABLED_MESSAGE)

    suspend fun testConnection(endpointUrl: String): Pair<Boolean, String> =
        false to LEGACY_RANKING_DISABLED_MESSAGE
}
