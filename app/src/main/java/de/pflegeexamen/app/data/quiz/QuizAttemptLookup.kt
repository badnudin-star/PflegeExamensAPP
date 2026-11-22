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

sealed interface AttemptLookup {
    data object NotConfigured : AttemptLookup
    data object InvalidToken : AttemptLookup
    /** No saved attempt was found at lookup time; a concurrent submission may still be in flight. */
    data object NotFound : AttemptLookup
    data class Closed(val date: String) : AttemptLookup
    data object Unavailable : AttemptLookup
    data class Found(val date: String, val correct: Int, val total: Int, val payloadHash: String?) : AttemptLookup
}

class AttemptResponse(val status: Int, val body: ByteArray)
fun interface AttemptLookupTransport {
    suspend fun fetch(url: String, token: String): AttemptResponse
}

@Serializable
private data class ResultBody(val date: String, val correct: Int? = null, val total: Int? = null, val payloadHash: String? = null, val status: String? = null)

/** Read-only reconciliation; never retries or submits an attempt, and does not store credentials. */
class QuizAttemptLookup(private val leaderboardUrl: String, private val transport: AttemptLookupTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(date: String, token: String): AttemptLookup {
        if (leaderboardUrl.isBlank()) return AttemptLookup.NotConfigured
        val base = runCatching {
            val uri = URI(leaderboardUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
            uri
        }.getOrNull() ?: return AttemptLookup.Unavailable
        if (!Regex("^[0-9a-f]{64}$").matches(token)) return AttemptLookup.InvalidToken
        val validDate = runCatching {
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date) && LocalDate.parse(date).toString() == date
        }.getOrDefault(false)
        if (!validDate) return AttemptLookup.Unavailable
        val url = base.resolve("/quiz/attempt/result?date=$date").toASCIIString()
        return try {
            val response = transport.fetch(url, token)
            if (response.status == 401) return AttemptLookup.InvalidToken
            if (response.status != 200 && response.status != 404 && response.status != 410) return AttemptLookup.Unavailable
            if (response.body.size > MAX_RESPONSE_BYTES) return AttemptLookup.Unavailable
            val body = runCatching {
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
                json.decodeFromString<ResultBody>(text)
            }.getOrNull() ?: return AttemptLookup.Unavailable
            if (body.date != date) return AttemptLookup.Unavailable
            if (response.status == 404) return AttemptLookup.NotFound
            if (response.status == 410) return if (body.status == "closed" && body.correct == null && body.total == null && body.payloadHash == null)
                AttemptLookup.Closed(date) else AttemptLookup.Unavailable
            val correct = body.correct ?: return AttemptLookup.Unavailable
            if (body.total != 50 || correct !in 0..50) return AttemptLookup.Unavailable
            if (body.payloadHash != null && !Regex("^[0-9a-f]{64}$").matches(body.payloadHash)) return AttemptLookup.Unavailable
            AttemptLookup.Found(date, correct, 50, body.payloadHash)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AttemptLookup.Unavailable
        }
    }

    companion object { const val MAX_RESPONSE_BYTES = 4096 }
}

class HttpsAttemptLookupTransport : AttemptLookupTransport {
    override suspend fun fetch(url: String, token: String): AttemptResponse = withContext(Dispatchers.IO) {
        // Defense in depth: never send the token to an arbitrary URL or over HTTP.
        val uri = URI(url)
        val day = uri.rawQuery?.takeIf { it.startsWith("date=") }?.removePrefix("date=")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/quiz/attempt/result" && uri.rawFragment == null &&
            day != null && Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(day) &&
            LocalDate.parse(day).toString() == day && Regex("^[0-9a-f]{64}$").matches(token))
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            if (status != 200 && status != 404 && status != 410) return@withContext AttemptResponse(status, byteArrayOf())
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            if (stream == null) return@withContext AttemptResponse(status, byteArrayOf())
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > QuizAttemptLookup.MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    out.write(buffer, 0, count)
                }
                AttemptResponse(status, out.toByteArray())
            }
        } finally {
            connection?.disconnect()
        }
    }
}
