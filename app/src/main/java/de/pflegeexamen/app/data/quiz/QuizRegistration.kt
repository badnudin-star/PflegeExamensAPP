package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom

sealed interface RegistrationState {
    data object NotConfigured : RegistrationState
    data object InvalidName : RegistrationState
    data object NotPrepared : RegistrationState
    /** May also mean a previous registration is still in flight. */
    data object NotRegistered : RegistrationState
    data object StorageUnavailable : RegistrationState
    /** A previously requested deletion must be finished before account activity. */
    data object DeletionPending : RegistrationState
    data object NameConflict : RegistrationState
    /** The request may or may not have succeeded; keep the existing token and check status. */
    data object Uncertain : RegistrationState
    data class Registered(val username: String) : RegistrationState
}

fun interface RankingTokenGenerator { fun create(): String }
class SecureRankingTokenGenerator : RankingTokenGenerator {
    override fun create(): String {
        val bytes = ByteArray(32)
        return try {
            SecureRandom().nextBytes(bytes)
            val hex = "0123456789abcdef"
            buildString(64) { bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]); append(hex[value and 15])
            } }
        } finally { bytes.fill(0) }
    }
}

class RegistrationResponse(val status: Int, val body: ByteArray)
fun interface RegistrationTransport {
    suspend fun send(method: String, url: String, token: String, body: ByteArray?): RegistrationResponse
}

@Serializable
private data class RegistrationBody(val username: String)

/** Local-first, idempotent registration. Never retries automatically or overwrites a token. */
class QuizRegistration(
    private val leaderboardUrl: String,
    private val vault: RankingTokenVault,
    private val generator: RankingTokenGenerator,
    private val transport: RegistrationTransport
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun base(): URI? = runCatching {
        URI(leaderboardUrl).also { uri ->
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
        }
    }.getOrNull()

    private fun usernameFrom(response: RegistrationResponse): String? {
        if (response.body.size > MAX_RESPONSE_BYTES) return null
        return runCatching {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
            json.decodeFromString<RegistrationBody>(text).username.takeIf(::validName)
        }.getOrNull()
    }

    suspend fun register(username: String): RegistrationState = mutex.withLock {
        if (leaderboardUrl.isBlank()) return@withLock RegistrationState.NotConfigured
        val base = base() ?: return@withLock RegistrationState.Uncertain
        if (!validName(username)) return@withLock RegistrationState.InvalidName
        val token = when (val existing = vault.load()) {
            is TokenLoad.Available -> existing.token
            TokenLoad.Unavailable -> return@withLock RegistrationState.StorageUnavailable
            TokenLoad.Missing -> {
                val generated = try { generator.create() } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { return@withLock RegistrationState.StorageUnavailable }
                val saved = vault.saveNew(generated)
                if (saved != TokenSave.SAVED && saved != TokenSave.ALREADY_EXISTS) return@withLock RegistrationState.StorageUnavailable
                val reloaded = vault.load() as? TokenLoad.Available ?: return@withLock RegistrationState.StorageUnavailable
                if (saved == TokenSave.SAVED && reloaded.token != generated) return@withLock RegistrationState.StorageUnavailable
                reloaded.token
            }
        }
        try {
            val body = json.encodeToString(RegistrationBody(username)).toByteArray(Charsets.UTF_8)
            val response = transport.send("POST", base.resolve("/participants").toASCIIString(), token, body)
            when (response.status) {
                200, 201 -> if (usernameFrom(response) == username) RegistrationState.Registered(username) else RegistrationState.Uncertain
                409 -> RegistrationState.NameConflict
                else -> RegistrationState.Uncertain
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { RegistrationState.Uncertain }
    }

    suspend fun status(): RegistrationState = mutex.withLock {
        if (leaderboardUrl.isBlank()) return@withLock RegistrationState.NotConfigured
        val base = base() ?: return@withLock RegistrationState.Uncertain
        val token = when (val existing = vault.load()) {
            TokenLoad.Missing -> return@withLock RegistrationState.NotPrepared
            TokenLoad.Unavailable -> return@withLock RegistrationState.StorageUnavailable
            is TokenLoad.Available -> existing.token
        }
        try {
            val response = transport.send("GET", base.resolve("/participants/me").toASCIIString(), token, null)
            when (response.status) {
                200 -> usernameFrom(response)?.let { RegistrationState.Registered(it) } ?: RegistrationState.Uncertain
                404 -> RegistrationState.NotRegistered
                else -> RegistrationState.Uncertain
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { RegistrationState.Uncertain }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 4096
        fun validName(name: String): Boolean = name.length in 2..24 && name == name.trim() &&
            Regex("^[\\p{L}\\p{N}_ .-]+$").matches(name) && !name.contains("  ")
    }
}

class HttpsRegistrationTransport : RegistrationTransport {
    override suspend fun send(method: String, url: String, token: String, body: ByteArray?): RegistrationResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("^[0-9a-f]{64}$").matches(token) &&
            ((method == "POST" && uri.rawPath == "/participants" && body != null && body.size <= 1024) ||
             (method == "GET" && uri.rawPath == "/participants/me" && body == null)))
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status != 200 && status != 201) return@withContext RegistrationResponse(status, byteArrayOf())
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > QuizRegistration.MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    out.write(buffer, 0, count)
                }
                RegistrationResponse(status, out.toByteArray())
            }
        } finally { connection?.disconnect() }
    }
}
