package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RankingRecoveryTest {
    private val token = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private val url = "https://rank.example/leaderboard"
    private class TokenMemory : EncryptedTokenStorage {
        var bytes: ByteArray? = null; var failWrite = false; var failRead = false
        override fun read(): ByteArray? { if (failRead) throw IllegalStateException(); return bytes?.clone() }
        override fun write(bytes: ByteArray) { if (failWrite) throw IllegalStateException(); this.bytes = bytes.clone() }
        override fun delete() { bytes = null }
    }
    private class PendingMemory : PendingAttemptPersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(data: ByteArray) { bytes = data.clone() }
        override fun delete() { bytes = null }
    }
    private class Journal : DeletionJournalPersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { this.bytes = bytes.clone() }
        override fun delete() { bytes = null }
    }
    private class Cipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    private data class Fixture(val disk: TokenMemory, val vault: RankingTokenVault, val pending: PendingAttemptStore,
        val pendingDisk: PendingMemory, val journal: Journal, val gate: RankingOperationGate)
    private fun fixture(): Fixture {
        val disk = TokenMemory(); val pending = PendingMemory(); val journal = Journal()
        return Fixture(disk, RankingTokenVault(disk, Cipher()), PendingAttemptStore(pending), pending, journal, RankingOperationGate(journal))
    }
    private fun client(f: Fixture, transport: RegistrationTransport) = RankingRecovery(url, f.vault, f.pending, f.gate, transport)
    private fun valid() = RegistrationResponse(200, """{"username":"Testperson"}""".toByteArray())

    @Test fun `exports grouped secret without network or logging it`() = runTest {
        val f=fixture(); assertEquals(TokenSave.SAVED,f.vault.saveNew(token)); var calls=0
        val client=client(f,RegistrationTransport { _,_,_,_ -> calls++; valid() })
        val result=client.export() as RecoveryExport.Ready
        assertEquals(token.chunked(8).joinToString("-"),result.code)
        assertFalse(result.toString().contains(token)); assertFalse(result.toString().contains(result.code)); assertEquals(0,calls)
        assertEquals(token,(f.vault.load() as TokenLoad.Available).token)
    }

    @Test fun `restore checks account and stores only after confirmed server status`() = runTest {
        val f=fixture(); var calls=0
        val client=client(f,RegistrationTransport { method,endpoint,credential,body ->
            calls++; assertEquals("GET",method); assertEquals("https://rank.example/participants/me",endpoint)
            assertEquals(token,credential); assertNull(body); assertEquals(TokenLoad.Missing,f.vault.load()); valid()
        })
        assertEquals(RecoveryRestore.Restored("Testperson"),client.restore(token.uppercase().chunked(8).joinToString("-")))
        assertEquals(1,calls); assertEquals(token,(f.vault.load() as TokenLoad.Available).token)
        assertFalse(f.disk.bytes!!.toString(Charsets.US_ASCII).contains(token))
    }

    @Test fun `bad codes unsafe URLs and preexisting credentials never write or send`() = runTest {
        val f=fixture(); var calls=0; val transport=RegistrationTransport { _,_,_,_ -> calls++; valid() }
        val client=client(f,transport)
        for (bad in listOf("", "a".repeat(63), "x".repeat(64), token + "-", token.chunked(8).joinToString("--"), "a".repeat(1000)))
            assertEquals(RecoveryRestore.InvalidCode,client.restore(bad))
        assertEquals(RecoveryRestore.NotConfigured,RankingRecovery("http://rank.example/leaderboard",f.vault,f.pending,f.gate,transport).restore(token))
        assertNull(f.disk.bytes); assertEquals(TokenSave.SAVED,f.vault.saveNew(other))
        val original=f.disk.bytes!!.clone()
        assertEquals(RecoveryRestore.ExistingDifferentAccount,client.restore(token))
        assertArrayEquals(original,f.disk.bytes); assertEquals(0,calls)
    }

    @Test fun `unconfirmed or malformed response never stores supplied code`() = runTest {
        for ((status,body) in listOf(404 to "",401 to "",503 to "",200 to "{}",200 to """{"username":"!"}""",200 to "x".repeat(4097))) {
            val f=fixture(); val client=client(f,RegistrationTransport { _,_,_,_ -> RegistrationResponse(status,body.toByteArray()) })
            assertEquals(RecoveryRestore.Unavailable,client.restore(token)); assertNull(f.disk.bytes)
        }
        val f=fixture(); val client=client(f,RegistrationTransport { _,_,_,_ -> throw IllegalStateException() })
        assertEquals(RecoveryRestore.Unavailable,client.restore(token)); assertNull(f.disk.bytes)
    }

    @Test fun `matching pending attempt survives recovery while another account blocks before network`() = runTest {
        val f=fixture(); val old=PendingMarker(date="2026-11-13",quizHash="a".repeat(64),accountHash=sha256(token.toByteArray()),payloadHash="b".repeat(64))
        assertTrue(f.pending.begin(old)); val original=f.pendingDisk.bytes!!.clone(); var calls=0
        val client=client(f,RegistrationTransport { _,_,_,_ -> calls++; valid() })
        assertEquals(RecoveryRestore.PendingAccountMismatch,client.restore(other)); assertEquals(0,calls)
        assertEquals(RecoveryRestore.Restored("Testperson"),client.restore(token)); assertEquals(1,calls)
        assertArrayEquals(original,f.pendingDisk.bytes); assertEquals(old,f.pending.read())
    }

    @Test fun `deletion journal and corrupt storage block export and restoration`() = runTest {
        val f=fixture(); assertEquals(TokenSave.SAVED,f.vault.saveNew(token)); var calls=0
        val client=client(f,RegistrationTransport { _,_,_,_ -> calls++; valid() })
        f.gate.save(DeletionJournal(accountHash=sha256(token.toByteArray())))
        assertEquals(RecoveryExport.DeletionPending,client.export())
        assertEquals(RecoveryRestore.DeletionPending,client.restore(token)); assertEquals(0,calls)
        f.journal.bytes="bad".toByteArray()
        assertEquals(RecoveryExport.DeletionPending,client.export())
        assertEquals(RecoveryRestore.DeletionPending,client.restore(token)); assertEquals(0,calls)
    }

    @Test fun `failed token write and corrupt pending fail closed`() = runTest {
        val f=fixture(); var calls=0; val client=client(f,RegistrationTransport { _,_,_,_ -> calls++; valid() })
        f.pendingDisk.bytes="broken".toByteArray()
        assertEquals(RecoveryRestore.StorageUnavailable,client.restore(token)); assertEquals(0,calls)
        f.pendingDisk.bytes=null; f.disk.failWrite=true
        assertEquals(RecoveryRestore.StorageUnavailable,client.restore(token)); assertEquals(1,calls); assertNull(f.disk.bytes)
    }

    @Test fun `recovery and deletion share the operation gate`() = runTest {
        val f=fixture(); val started=CompletableDeferred<Unit>(); val proceed=CompletableDeferred<Unit>()
        val client=client(f,RegistrationTransport { _,_,_,_ -> started.complete(Unit); proceed.await(); valid() })
        val restoring=async { client.restore(token) }; started.await()
        var deleteCalls=0
        val deletion=RankingAccountDeletion(url,f.vault,f.pending,f.gate,AccountDeletionTransport { _,_ -> deleteCalls++; DeletionResponse(204,"v1") })
        val deleting=async { deletion.delete() }
        assertEquals(0,deleteCalls); proceed.complete(Unit)
        assertEquals(RecoveryRestore.Restored("Testperson"),restoring.await())
        assertEquals(AccountDeletionResult.Deleted,deleting.await()); assertEquals(TokenLoad.Missing,f.vault.load()); assertEquals(1,deleteCalls)
    }

    @Test fun `cancellation propagates without saving token`() = runTest {
        val f=fixture(); val client=client(f,RegistrationTransport { _,_,_,_ -> throw CancellationException() })
        try { client.restore(token); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertNull(f.disk.bytes)
    }
}
