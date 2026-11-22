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

sealed interface AttemptClosure {
    data object Unavailable : AttemptClosure
    data class Closed(val date: String) : AttemptClosure
    data class Recorded(val date: String, val correct: Int, val total: Int, val payloadHash: String?) : AttemptClosure
}

class ClosureResponse(val status: Int, val body: ByteArray)
fun interface ClosureTransport {
    suspend fun send(url: String, token: String, body: ByteArray): ClosureResponse
}

@Serializable private data class CloseRequest(val date: String)
@Serializable private data class CloseBody(val date: String, val status: String, val correct: Int? = null, val total: Int? = null, val payloadHash: String? = null)

/** Explicit, irreversible request. A missing or malformed response never confirms closure. */
class QuizAttemptClose(private val leaderboardUrl: String, private val transport: ClosureTransport) {
    suspend fun close(date: String, token: String): AttemptClosure {
        val base = runCatching {
            URI(leaderboardUrl).also { uri ->
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
            }
        }.getOrNull() ?: return AttemptClosure.Unavailable
        val validDate = runCatching {
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date) && LocalDate.parse(date).toString() == date
        }.getOrDefault(false)
        if (!validDate || !Regex("^[0-9a-f]{64}$").matches(token)) return AttemptClosure.Unavailable
        return try {
            val response = transport.send(base.resolve("/quiz/attempt/close").toASCIIString(), token,
                Json.encodeToString(CloseRequest(date)).toByteArray(Charsets.UTF_8))
            if (response.status != 200 || response.body.size > MAX_RESPONSE_BYTES) return AttemptClosure.Unavailable
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
            val value = Json.decodeFromString<CloseBody>(text)
            if (value.date != date) return AttemptClosure.Unavailable
            when (value.status) {
                "closed" -> if (value.correct == null && value.total == null && value.payloadHash == null) AttemptClosure.Closed(date) else AttemptClosure.Unavailable
                "recorded" -> if (value.correct != null && value.correct in 0..50 && value.total == 50 &&
                    (value.payloadHash == null || Regex("^[0-9a-f]{64}$").matches(value.payloadHash)))
                    AttemptClosure.Recorded(date, value.correct, 50, value.payloadHash) else AttemptClosure.Unavailable
                else -> AttemptClosure.Unavailable
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { AttemptClosure.Unavailable }
    }
    companion object { const val MAX_RESPONSE_BYTES = 4096 }
}

class HttpsClosureTransport : ClosureTransport {
    override suspend fun send(url: String, token: String, body: ByteArray): ClosureResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/quiz/attempt/close" && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("^[0-9a-f]{64}$").matches(token) && body.size <= 1024)
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
            if (status != 200) return@withContext ClosureResponse(status, byteArrayOf())
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > QuizAttemptClose.MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    out.write(buffer, 0, count)
                }
                ClosureResponse(status, out.toByteArray())
            }
        } finally { connection?.disconnect() }
    }
}

sealed interface PendingClosureState {
    data object None : PendingClosureState
    data object StorageUnavailable : PendingClosureState
    data object CredentialUnavailable : PendingClosureState
    data object CredentialMismatch : PendingClosureState
    data class Uncertain(val date: String) : PendingClosureState
    data class Closed(val date: String) : PendingClosureState
    data class DifferentOrUnverifiedAttempt(val date: String) : PendingClosureState
    data class Recorded(val date: String, val correct: Int) : PendingClosureState
}

/** Caller must obtain explicit informed consent before invoking this irreversible action. */
class PendingQuizClosure(
    private val store: PendingAttemptStore,
    private val vault: RankingTokenVault,
    private val client: QuizAttemptClose
) {
    suspend fun close(): PendingClosureState {
        val marker = try { store.read() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return PendingClosureState.StorageUnavailable } ?: return PendingClosureState.None
        val token = (vault.load() as? TokenLoad.Available)?.token ?: return PendingClosureState.CredentialUnavailable
        if (sha256(token.toByteArray(Charsets.US_ASCII)) != marker.accountHash) return PendingClosureState.CredentialMismatch
        marker.correct?.let { return PendingClosureState.Recorded(marker.date, it) }
        return when (val result = client.close(marker.date, token)) {
            is AttemptClosure.Closed -> try {
                if (store.clearClosed(marker)) PendingClosureState.Closed(marker.date) else PendingClosureState.StorageUnavailable
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { PendingClosureState.StorageUnavailable }
            is AttemptClosure.Recorded -> {
                if (result.payloadHash != marker.payloadHash) return PendingClosureState.DifferentOrUnverifiedAttempt(marker.date)
                try {
                    if (store.record(marker, result.correct)) PendingClosureState.Recorded(marker.date, result.correct)
                    else PendingClosureState.StorageUnavailable
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { PendingClosureState.StorageUnavailable }
            }
            AttemptClosure.Unavailable -> PendingClosureState.Uncertain(marker.date)
        }
    }
}
