package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale

sealed interface RecoveryExport {
    data object NotConfigured : RecoveryExport
    data object DeletionPending : RecoveryExport
    data object NoAccount : RecoveryExport
    data object StorageUnavailable : RecoveryExport
    /** The code is a bearer credential; never log or include it in diagnostics. */
    class Ready internal constructor(val code: String) : RecoveryExport {
        override fun toString(): String = "Ready(<redacted>)"
    }
}

sealed interface RecoveryRestore {
    data object NotConfigured : RecoveryRestore
    data object DeletionPending : RecoveryRestore
    data object InvalidCode : RecoveryRestore
    data object ExistingDifferentAccount : RecoveryRestore
    data object PendingAccountMismatch : RecoveryRestore
    data object StorageUnavailable : RecoveryRestore
    /** Includes unconfirmed/absent/deleted account, rate limiting, network failure and malformed replies. */
    data object Unavailable : RecoveryRestore
    data class Restored(val username: String) : RecoveryRestore
}

@Serializable private data class RecoveryAccountBody(val username: String)

/** Explicit opt-in export and restore; never sends a code anywhere except as the HTTPS authorization header. */
class RankingRecovery(
    private val leaderboardUrl: String,
    private val vault: RankingTokenVault,
    private val pending: PendingAttemptStore,
    private val gate: RankingOperationGate,
    private val transport: RegistrationTransport
) {
    private fun base(): URI? = runCatching {
        URI(leaderboardUrl).also { uri ->
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawPath == "/leaderboard" && uri.rawQuery == null && uri.rawFragment == null)
        }
    }.getOrNull()

    suspend fun export(): RecoveryExport = gate.unlessDeleting<RecoveryExport>(RecoveryExport.DeletionPending) {
        if (base() == null) return@unlessDeleting RecoveryExport.NotConfigured
        when (val stored = vault.load()) {
            TokenLoad.Missing -> RecoveryExport.NoAccount
            TokenLoad.Unavailable -> RecoveryExport.StorageUnavailable
            is TokenLoad.Available -> RecoveryExport.Ready(stored.token.chunked(8).joinToString("-"))
        }
    }

    suspend fun restore(code: String): RecoveryRestore = gate.unlessDeleting<RecoveryRestore>(RecoveryRestore.DeletionPending) {
        val base = base() ?: return@unlessDeleting RecoveryRestore.NotConfigured
        val token = normalize(code) ?: return@unlessDeleting RecoveryRestore.InvalidCode
        val existing = vault.load()
        when (existing) {
            TokenLoad.Unavailable -> return@unlessDeleting RecoveryRestore.StorageUnavailable
            is TokenLoad.Available -> if (existing.token != token) return@unlessDeleting RecoveryRestore.ExistingDifferentAccount
            TokenLoad.Missing -> Unit
        }
        val marker = try { pending.read() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@unlessDeleting RecoveryRestore.StorageUnavailable }
        if (marker != null && marker.accountHash != sha256(token.toByteArray(Charsets.US_ASCII)))
            return@unlessDeleting RecoveryRestore.PendingAccountMismatch
        try {
            val response = transport.send("GET", base.resolve("/participants/me").toASCIIString(), token, null)
            if (response.status != 200 || response.body.size > QuizRegistration.MAX_RESPONSE_BYTES) return@unlessDeleting RecoveryRestore.Unavailable
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body)).toString()
            val username = Json { ignoreUnknownKeys = true }.decodeFromString<RecoveryAccountBody>(text).username
            if (!QuizRegistration.validName(username)) return@unlessDeleting RecoveryRestore.Unavailable
            if (existing is TokenLoad.Missing) {
                val saved = vault.saveNew(token)
                if (saved != TokenSave.SAVED && saved != TokenSave.ALREADY_EXISTS) return@unlessDeleting RecoveryRestore.StorageUnavailable
            }
            // A competing or interrupted write must never be accepted as this account.
            val reloaded = vault.load() as? TokenLoad.Available ?: return@unlessDeleting RecoveryRestore.StorageUnavailable
            if (reloaded.token != token) return@unlessDeleting RecoveryRestore.ExistingDifferentAccount
            RecoveryRestore.Restored(username)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { RecoveryRestore.Unavailable }
    }

    companion object {
        /** Accept either 64 hex digits or eight groups of eight separated by spaces or hyphens. */
        internal fun normalize(input: String): String? {
            if (input.length > 128) return null
            val text = input.trim()
            if (!Regex("^[0-9a-fA-F]{64}$").matches(text) &&
                !Regex("^[0-9a-fA-F]{8}(?:[- ][0-9a-fA-F]{8}){7}$").matches(text)) return null
            return text.replace("-", "").replace(" ", "").lowercase(Locale.ROOT)
        }
    }
}
