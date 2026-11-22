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

interface DeletionJournalPersistence {
    /** Null only if no journal or recovery file exists; errors must throw. */
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

@Serializable
internal data class DeletionJournal(val version: Int = 1, val accountHash: String, val confirmed: Boolean = false)

/** A single shared lock must cover all mutations and all stores for the same account files. */
class RankingOperationGate(private val storage: DeletionJournalPersistence, private val mutex: Mutex = Mutex()) {
    internal fun read(): DeletionJournal? {
        val bytes = storage.read() ?: return null
        require(bytes.size <= 1024)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val record = Json.decodeFromString<DeletionJournal>(text)
        require(record.version == 1 && Regex("^[0-9a-f]{64}$").matches(record.accountHash))
        return record
    }
    internal fun save(record: DeletionJournal) {
        val bytes = Json.encodeToString(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024)
        storage.write(bytes)
        check(read() == record)
    }
    internal fun clear(expected: DeletionJournal): Boolean {
        if (read() != expected || !expected.confirmed) return false
        storage.delete()
        return storage.read() == null
    }
    suspend fun <T> unlessDeleting(blocked: T, action: suspend () -> T): T = mutex.withLock {
        val exists = try { withContext(Dispatchers.IO) { read() != null } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { true }
        if (exists) blocked else action()
    }
    internal suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock { action() }
}

class DeletionResponse(val status: Int, val receipt: String?)
fun interface AccountDeletionTransport { suspend fun delete(url: String, token: String): DeletionResponse }

sealed interface AccountDeletionResult {
    data object NotConfigured : AccountDeletionResult
    data object NoAccount : AccountDeletionResult
    data object CredentialUnavailable : AccountDeletionResult
    /** No valid receipt. The request may still complete; retry explicitly with the same token. */
    data object Uncertain : AccountDeletionResult
    /** Includes corrupt journal, mismatched account or incomplete local cleanup. */
    data object LocalCleanupIncomplete : AccountDeletionResult
    data object Deleted : AccountDeletionResult
}

/** The caller must obtain explicit informed consent. No automatic network retry. */
class RankingAccountDeletion(
    private val leaderboardUrl: String,
    private val vault: RankingTokenVault,
    private val pending: PendingAttemptStore,
    private val gate: RankingOperationGate,
    private val transport: AccountDeletionTransport
) {
    suspend fun delete(): AccountDeletionResult = gate.locked {
        if (leaderboardUrl.isBlank()) return@locked AccountDeletionResult.NotConfigured
        val base = runCatching {
            URI(leaderboardUrl).also { uri -> require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null) }
        }.getOrNull() ?: return@locked AccountDeletionResult.NotConfigured
        try {
            val loaded = vault.load()
            var journal: DeletionJournal = withContext(Dispatchers.IO) { gate.read() } ?: run {
                val token = when (loaded) {
                    TokenLoad.Missing -> return@locked AccountDeletionResult.NoAccount
                    TokenLoad.Unavailable -> return@locked AccountDeletionResult.CredentialUnavailable
                    is TokenLoad.Available -> loaded.token
                }
                val created = DeletionJournal(accountHash = sha256(token.toByteArray(Charsets.US_ASCII)))
                withContext(Dispatchers.IO) { gate.save(created) }
                created
            }
            if (!journal.confirmed) {
                val token = (loaded as? TokenLoad.Available)?.token ?: return@locked AccountDeletionResult.CredentialUnavailable
                if (sha256(token.toByteArray(Charsets.US_ASCII)) != journal.accountHash) return@locked AccountDeletionResult.LocalCleanupIncomplete
                val response = try { transport.delete(base.resolve("/participants/me").toASCIIString(), token) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { return@locked AccountDeletionResult.Uncertain }
                // A plain 204 from an older worker is not sufficient to guarantee the barrier.
                if (response.status != 204 || response.receipt != "v1") return@locked AccountDeletionResult.Uncertain
                val confirmed = journal.copy(confirmed = true)
                withContext(Dispatchers.IO) { gate.save(confirmed) }
                journal = confirmed
            }
            if (!pending.clearForDeletedAccount(journal.accountHash) || !vault.clearMatchingAfterDeletion(journal.accountHash))
                return@locked AccountDeletionResult.LocalCleanupIncomplete
            if (!withContext(Dispatchers.IO) { gate.clear(journal) }) return@locked AccountDeletionResult.LocalCleanupIncomplete
            AccountDeletionResult.Deleted
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { AccountDeletionResult.LocalCleanupIncomplete }
    }
}

class HttpsAccountDeletionTransport : AccountDeletionTransport {
    override suspend fun delete(url: String, token: String): DeletionResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/participants/me" && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("^[0-9a-f]{64}$").matches(token))
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            DeletionResponse(status, connection.getHeaderField("X-Quiz-Deletion-Receipt"))
        } finally { connection?.disconnect() }
    }
}
