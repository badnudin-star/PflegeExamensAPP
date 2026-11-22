package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate

internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Contains no raw token, questions, or selected answers. Hashes are still sensitive metadata. */
@Serializable
internal data class PendingMarker(
    val version: Int = 1, val date: String, val quizHash: String, val accountHash: String,
    val payloadHash: String, val correct: Int? = null
)

interface PendingAttemptPersistence {
    /** Return null only when no file or recovery file exists; errors must throw. */
    fun read(): ByteArray?
    fun write(data: ByteArray)
    fun delete()
}

/** Shares a single lock across all stores for the same backing file. */
class PendingAttemptStore(private val storage: PendingAttemptPersistence, private val lock: Mutex = Mutex()) {
    private val json = Json
    private fun readMarker(): PendingMarker? {
        val bytes = storage.read() ?: return null
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val marker = json.decodeFromString<PendingMarker>(text)
        val validDate = runCatching {
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(marker.date) && LocalDate.parse(marker.date).toString() == marker.date
        }.getOrDefault(false)
        val hash = Regex("^[0-9a-f]{64}$")
        require(marker.version == 1 && validDate && hash.matches(marker.quizHash) && hash.matches(marker.accountHash) &&
            hash.matches(marker.payloadHash) && (marker.correct == null || marker.correct in 0..50))
        return marker
    }
    private fun writeMarker(marker: PendingMarker) {
        val raw = json.encodeToString(marker).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_BYTES)
        storage.write(raw)
        check(readMarker() == marker)
    }
    internal suspend fun begin(marker: PendingMarker): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (readMarker() != null) return@withLock false
            require(marker.correct == null)
            writeMarker(marker)
            true
        }
    }
    internal suspend fun read(): PendingMarker? = withContext(Dispatchers.IO) { lock.withLock { readMarker() } }
    internal suspend fun record(expected: PendingMarker, correct: Int): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            require(correct in 0..50 && expected.correct == null)
            val current = readMarker() ?: return@withLock false
            val next = expected.copy(correct = correct)
            if (current == next) return@withLock true
            if (current != expected) return@withLock false
            writeMarker(next)
            true
        }
    }
    /** Remove only the same unresolved marker after an authoritative server closure. */
    internal suspend fun clearClosed(expected: PendingMarker): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (expected.correct != null || readMarker() != expected) return@withLock false
            storage.delete()
            check(storage.read() == null)
            true
        }
    }
    internal suspend fun acknowledge(accountHash: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val marker = readMarker() ?: return@withLock false
            if (marker.correct == null || marker.accountHash != accountHash) return@withLock false
            storage.delete()
            check(storage.read() == null)
            true
        }
    }
    companion object { const val MAX_BYTES = 2048 }
}

sealed interface PendingQuizState {
    data object None : PendingQuizState
    data object StorageUnavailable : PendingQuizState
    data object CredentialUnavailable : PendingQuizState
    data object CredentialMismatch : PendingQuizState
    /** No result has been confirmed. A prior request may still be running or already committed. */
    data class Waiting(val date: String) : PendingQuizState
    /** A result exists but cannot be proven to be this exact request. The marker remains blocked. */
    data class DifferentOrUnverifiedAttempt(val date: String) : PendingQuizState
    data class Recorded(val date: String, val correct: Int, val total: Int = 50) : PendingQuizState
}

fun interface PendingResultLookup { suspend fun lookup(date: String, token: String): AttemptLookup }

/** Reconciliation never submits or automatically discards an unresolved attempt. */
class PendingQuizReconciliation(
    private val store: PendingAttemptStore,
    private val vault: RankingTokenVault,
    private val lookup: PendingResultLookup
) {
    suspend fun reconcile(): PendingQuizState {
        val marker = try { store.read() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return PendingQuizState.StorageUnavailable } ?: return PendingQuizState.None
        val token = when (val value = vault.load()) {
            is TokenLoad.Available -> value.token
            else -> return PendingQuizState.CredentialUnavailable
        }
        if (sha256(token.toByteArray(Charsets.US_ASCII)) != marker.accountHash) return PendingQuizState.CredentialMismatch
        marker.correct?.let { return PendingQuizState.Recorded(marker.date, it) }
        val found = try { lookup.lookup(marker.date, token) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return PendingQuizState.Waiting(marker.date) }
        if (found !is AttemptLookup.Found || found.date != marker.date || found.total != 50 || found.correct !in 0..50)
            return PendingQuizState.Waiting(marker.date)
        if (found.payloadHash != marker.payloadHash) return PendingQuizState.DifferentOrUnverifiedAttempt(marker.date)
        return try {
            if (store.record(marker, found.correct)) PendingQuizState.Recorded(marker.date, found.correct)
            else PendingQuizState.StorageUnavailable
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { PendingQuizState.StorageUnavailable }
    }

    /** Only acknowledge a recorded result; unresolved attempts are never cleared here. */
    suspend fun acknowledgeRecorded(): Boolean {
        val token = (vault.load() as? TokenLoad.Available)?.token ?: return false
        return try { store.acknowledge(sha256(token.toByteArray(Charsets.US_ASCII))) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }
}
