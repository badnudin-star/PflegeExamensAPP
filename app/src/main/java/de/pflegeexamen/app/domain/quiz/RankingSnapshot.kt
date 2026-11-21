package de.pflegeexamen.app.domain.quiz

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only public quiz ranking. Never contains device tokens or local practice points. */
@Serializable
data class QuizRankingEntry(val rank: Int, val username: String, val points: Long, val quizzes: Long)

@Serializable
data class QuizRankingSnapshot(
    val generatedAtMillis: Long,
    val nextUpdateAtMillis: Long,
    val totalParticipants: Long,
    val entries: List<QuizRankingEntry>
)

object RankingSnapshotCodec {
    const val MAX_BYTES = 128 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: ByteArray): QuizRankingSnapshot? {
        if (raw.size > MAX_BYTES) return null
        val snapshot = runCatching {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
            json.decodeFromString<QuizRankingSnapshot>(text)
        }.getOrNull() ?: return null
        if (snapshot.generatedAtMillis <= 0 || snapshot.nextUpdateAtMillis <= snapshot.generatedAtMillis ||
            snapshot.nextUpdateAtMillis - snapshot.generatedAtMillis > 3_600_000 ||
            snapshot.generatedAtMillis > 253_402_300_799_999L || snapshot.totalParticipants < snapshot.entries.size ||
            snapshot.entries.size > 100) return null
        if (snapshot.entries.withIndex().any { (i, entry) ->
                entry.rank != i + 1 || entry.username.isBlank() || entry.username.length > 24 ||
                    entry.username.any { it.isISOControl() } || entry.quizzes <= 0 || entry.quizzes > Long.MAX_VALUE / 50 ||
                    entry.points < 0 || entry.points > entry.quizzes * 50
            }) return null
        if (snapshot.entries.zipWithNext().any { (a, b) -> a.points < b.points }) return null
        return snapshot
    }
}
