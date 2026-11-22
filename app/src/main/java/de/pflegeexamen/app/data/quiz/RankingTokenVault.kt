package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface TokenLoad {
    data object Missing : TokenLoad
    /** Includes lost/inaccessible key, corrupt ciphertext, and storage errors. Nothing is deleted. */
    data object Unavailable : TokenLoad
    class Available internal constructor(val token: String) : TokenLoad {
        override fun toString(): String = "Available(<redacted>)"
    }
}

enum class TokenSave { SAVED, ALREADY_EXISTS, INVALID_TOKEN, UNAVAILABLE }

/** Implementations must return null only when no token file or recovery file exists. */
interface EncryptedTokenStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

interface RankingTokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Never logs tokens, overwrites existing token data, or regenerates a lost key during load. */
class RankingTokenVault(
    private val storage: EncryptedTokenStorage,
    private val cipher: RankingTokenCipher,
    private val lock: Mutex = Mutex()
) {

    suspend fun load(): TokenLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val encrypted = storage.read() ?: return@withLock TokenLoad.Missing
                if (encrypted.isEmpty() || encrypted.size > MAX_ENCRYPTED_BYTES) return@withLock TokenLoad.Unavailable
                val plain = cipher.decrypt(encrypted)
                try {
                    val token = plain.toString(Charsets.US_ASCII)
                    if (!TOKEN.matches(token)) TokenLoad.Unavailable else TokenLoad.Available(token)
                } finally { plain.fill(0) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { TokenLoad.Unavailable }
        }
    }

    suspend fun saveNew(token: String): TokenSave = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!TOKEN.matches(token)) return@withLock TokenSave.INVALID_TOKEN
            try {
                if (storage.read() != null) return@withLock TokenSave.ALREADY_EXISTS
                val plain = token.toByteArray(Charsets.US_ASCII)
                val encrypted = try { cipher.encrypt(plain) } finally { plain.fill(0) }
                if (encrypted.isEmpty() || encrypted.size > MAX_ENCRYPTED_BYTES) return@withLock TokenSave.UNAVAILABLE
                storage.write(encrypted)
                TokenSave.SAVED
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { TokenSave.UNAVAILABLE }
        }
    }

    /** Only call after a durable deletion receipt. Never remove a different account's token. */
    internal suspend fun clearMatchingAfterDeletion(accountHash: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val encrypted = storage.read() ?: return@withLock true
                if (encrypted.isEmpty() || encrypted.size > MAX_ENCRYPTED_BYTES) return@withLock false
                val plain = cipher.decrypt(encrypted)
                try {
                    val token = plain.toString(Charsets.US_ASCII)
                    if (!TOKEN.matches(token) || sha256(token.toByteArray(Charsets.US_ASCII)) != accountHash) return@withLock false
                } finally { plain.fill(0) }
                storage.delete()
                storage.read() == null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }
    }

    companion object {
        const val MAX_ENCRYPTED_BYTES = 1024
        private val TOKEN = Regex("^[0-9a-f]{64}$")
    }
}
