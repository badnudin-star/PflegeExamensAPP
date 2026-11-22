package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.time.LocalDate

@Serializable
data class RankedAnswer(val id: String, val option: Int)

@Serializable
private data class SubmissionBody(val date: String, val fingerprint: String, val answers: List<RankedAnswer>)
@Serializable
private data class ScoreBody(val date: String, val correct: Int, val total: Int)

sealed interface SubmissionResult {
    data object NotConfigured : SubmissionResult
    data object InvalidInput : SubmissionResult
    data object NoAccount : SubmissionResult
    data object CredentialUnavailable : SubmissionResult
    data object CredentialRejected : SubmissionResult
    data object AnotherAttemptPending : SubmissionResult
    data object PendingStorageUnavailable : SubmissionResult
    /** Could be an already recorded attempt or changed quiz; reconcile instead of retrying blindly. */
    data object Conflict : SubmissionResult
    /** The server may have committed the attempt; reconcile before any further action. */
    data object Uncertain : SubmissionResult
    data class Recorded(val date: String, val correct: Int, val total: Int) : SubmissionResult
}

class SubmissionResponse(val status: Int, val body: ByteArray)
fun interface QuizSubmissionTransport {
    suspend fun send(url: String, token: String, body: ByteArray): SubmissionResponse
}

/** One explicit call sends at most one request, after the durable pending marker is saved. */
class QuizAttemptSubmit(
    private val leaderboardUrl: String,
    private val vault: RankingTokenVault,
    private val pending: PendingAttemptStore,
    private val transport: QuizSubmissionTransport
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun submit(date: String, fingerprint: String, answers: List<RankedAnswer>): SubmissionResult {
        if (leaderboardUrl.isBlank()) return SubmissionResult.NotConfigured
        val base = runCatching {
            URI(leaderboardUrl).also { uri ->
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
            }
        }.getOrNull() ?: return SubmissionResult.InvalidInput
        val validDate = runCatching {
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date) && LocalDate.parse(date).toString() == date
        }.getOrDefault(false)
        if (!validDate || !Regex("^[0-9a-f]{64}$").matches(fingerprint) || answers.size != 50 ||
            answers.any { it.id.isBlank() || it.id.length > 200 || it.id.any { ch -> ch.isISOControl() } || it.option !in 0..3 } ||
            answers.map { it.id }.distinct().size != answers.size) return SubmissionResult.InvalidInput
        val body = json.encodeToString(SubmissionBody(date, fingerprint, answers)).toByteArray(Charsets.UTF_8)
        if (body.size > MAX_REQUEST_BYTES) return SubmissionResult.InvalidInput
        val token = when (val value = vault.load()) {
            TokenLoad.Missing -> return SubmissionResult.NoAccount
            TokenLoad.Unavailable -> return SubmissionResult.CredentialUnavailable
            is TokenLoad.Available -> value.token
        }
        val marker = PendingMarker(date = date, quizHash = fingerprint,
            accountHash = sha256(token.toByteArray(Charsets.US_ASCII)), payloadHash = sha256(body))
        val started = try { pending.begin(marker) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return SubmissionResult.PendingStorageUnavailable }
        if (!started) return SubmissionResult.AnotherAttemptPending
        return try {
            val response = transport.send(base.resolve("/quiz/attempt").toASCIIString(), token, body)
            when (response.status) {
                401 -> SubmissionResult.CredentialRejected
                409 -> SubmissionResult.Conflict
                201 -> {
                    if (response.body.size > MAX_RESPONSE_BYTES) return SubmissionResult.Uncertain
                    val score = runCatching {
                        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
                        json.decodeFromString<ScoreBody>(text)
                    }.getOrNull()
                    if (score == null || score.date != date || score.total != 50 || score.correct !in 0..50)
                        SubmissionResult.Uncertain
                    else if (pending.record(marker, score.correct)) SubmissionResult.Recorded(date, score.correct, score.total)
                    else SubmissionResult.Uncertain
                }
                else -> SubmissionResult.Uncertain
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SubmissionResult.Uncertain }
    }

    companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 4096
    }
}

class HttpsQuizSubmissionTransport : QuizSubmissionTransport {
    override suspend fun send(url: String, token: String, body: ByteArray): SubmissionResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/quiz/attempt" && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("^[0-9a-f]{64}$").matches(token) && body.size <= QuizAttemptSubmit.MAX_REQUEST_BYTES)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 201) return@withContext SubmissionResponse(status, byteArrayOf())
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > QuizAttemptSubmit.MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    out.write(buffer, 0, count)
                }
                SubmissionResponse(status, out.toByteArray())
            }
        } finally { connection?.disconnect() }
    }
}
