package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.time.LocalDate

/** The server's fingerprint is an opaque version identifier, not a locally verified digest. */
@Serializable
data class RankedQuizQuestion(
    val id: String,
    val topic: String,
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

@Serializable
private data class QuizBody(val date: String, val fingerprint: String, val questions: List<RankedQuizQuestion>)

data class RankedQuiz(val date: String, val fingerprint: String, val questions: List<RankedQuizQuestion>)

sealed interface RankedQuizLoad {
    data object NotConfigured : RankedQuizLoad
    /** Includes missing quizzes, unsafe endpoints, network failures and invalid server responses. */
    data object Unavailable : RankedQuizLoad
    data class Ready(val quiz: RankedQuiz) : RankedQuizLoad
}

class RankedQuizResponse(val status: Int, val body: ByteArray)
fun interface RankedQuizTransport {
    /** This is a public endpoint: no token or answers are needed or sent. */
    suspend fun fetch(url: String): RankedQuizResponse
}

/** Not wired to the UI. Each explicit call fetches once, with no caching or retry. */
class RankedQuizClient(private val leaderboardUrl: String, private val transport: RankedQuizTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): RankedQuizLoad {
        if (leaderboardUrl.isBlank()) return RankedQuizLoad.NotConfigured
        val base = runCatching {
            URI(leaderboardUrl).also { uri ->
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
            }
        }.getOrNull() ?: return RankedQuizLoad.Unavailable
        return try {
            val response = transport.fetch(base.resolve("/quiz/today").toASCIIString())
            if (response.status != 200 || response.body.size > MAX_RESPONSE_BYTES) return RankedQuizLoad.Unavailable
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
            val body = json.decodeFromString<QuizBody>(text)
            val validDate = runCatching {
                Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(body.date) && LocalDate.parse(body.date).toString() == body.date
            }.getOrDefault(false)
            if (!validDate || !Regex("^[0-9a-f]{64}$").matches(body.fingerprint) || body.questions.size != 50)
                return RankedQuizLoad.Unavailable
            val ids = HashSet<String>()
            if (body.questions.any { q ->
                q.id.isBlank() || q.id.length > 200 || q.id.any { it.isISOControl() } || !ids.add(q.id) ||
                    q.topic.isBlank() || q.question.isBlank() || q.explanation.isBlank() ||
                    q.options.size != 4 || q.options.any { it.isBlank() } || q.options.distinct().size != 4 ||
                    q.correctIndex !in 0..3
            }) return RankedQuizLoad.Unavailable
            RankedQuizLoad.Ready(RankedQuiz(body.date, body.fingerprint, body.questions))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RankedQuizLoad.Unavailable
        }
    }

    companion object { const val MAX_RESPONSE_BYTES = 1024 * 1024 }
}

class HttpsRankedQuizTransport : RankedQuizTransport {
    override suspend fun fetch(url: String): RankedQuizResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/quiz/today" && uri.rawQuery == null && uri.rawFragment == null)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status != 200) return@withContext RankedQuizResponse(status, byteArrayOf())
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > RankedQuizClient.MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    out.write(buffer, 0, count)
                }
                RankedQuizResponse(status, out.toByteArray())
            }
        } finally { connection?.disconnect() }
    }
}
