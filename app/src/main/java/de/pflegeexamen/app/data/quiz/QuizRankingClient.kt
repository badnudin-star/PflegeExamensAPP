package de.pflegeexamen.app.data.quiz

import de.pflegeexamen.app.domain.quiz.QuizRankingSnapshot
import de.pflegeexamen.app.domain.quiz.RankingSnapshotCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed interface RankingLoad {
    data object NotConfigured : RankingLoad
    data object Unavailable : RankingLoad
    data class Ready(val snapshot: QuizRankingSnapshot) : RankingLoad
}

/** No credentials, device identifiers, scores or quiz answers are sent. */
fun interface PublicRankingTransport {
    suspend fun fetch(url: String): ByteArray?
}

class QuizRankingClient(private val url: String, private val transport: PublicRankingTransport) {
    suspend fun load(): RankingLoad {
        if (url.isBlank()) return RankingLoad.NotConfigured
        val safe = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
        if (!safe) return RankingLoad.Unavailable
        return try {
            val bytes = transport.fetch(url) ?: return RankingLoad.Unavailable
            RankingSnapshotCodec.parse(bytes)?.let { RankingLoad.Ready(it) } ?: RankingLoad.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RankingLoad.Unavailable
        }
    }
}

class HttpsRankingTransport : PublicRankingTransport {
    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return@withContext null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > RankingSnapshotCodec.MAX_BYTES) return@withContext null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally {
            connection?.disconnect()
        }
    }
}
