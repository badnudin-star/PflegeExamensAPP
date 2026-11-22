package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RankingAccountDeletionTest {
    private val token = "ab".repeat(32)
    private val hash = sha256(token.toByteArray(Charsets.US_ASCII))
    private val url = "https://rank.example/leaderboard"
    private class Journal : DeletionJournalPersistence {
        var data: ByteArray? = null
        var failWrite = false
        override fun read() = data?.clone()
        override fun write(bytes: ByteArray) { if (failWrite) throw IllegalStateException(); data = bytes.clone() }
        override fun delete() { data = null }
    }
    private class TokenStorage : EncryptedTokenStorage {
        var data: ByteArray? = null
        var failDelete = false
        override fun read() = data?.clone()
        override fun write(bytes: ByteArray) { data = bytes.clone() }
        override fun delete() { if (failDelete) throw IllegalStateException(); data = null }
    }
    private class PendingStorage : PendingAttemptPersistence {
        var data: ByteArray? = null
        var failDelete = false
        override fun read() = data?.clone()
        override fun write(data: ByteArray) { this.data = data.clone() }
        override fun delete() { if (failDelete) throw IllegalStateException(); data = null }
    }
    private class Cipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.clone()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.clone()
    }
    private data class Fixture(val journal: Journal, val tokens: TokenStorage, val pendingDisk: PendingStorage,
        val gate: RankingOperationGate, val vault: RankingTokenVault, val pending: PendingAttemptStore)
    private suspend fun fixture(withPending: Boolean = true): Fixture {
        val journal = Journal(); val tokens = TokenStorage(); val disk = PendingStorage()
        val vault = RankingTokenVault(tokens, Cipher()); assertEquals(TokenSave.SAVED, vault.saveNew(token))
        val pending = PendingAttemptStore(disk)
        if (withPending) assertTrue(pending.begin(PendingMarker(date="2026-11-13",quizHash="b".repeat(64),accountHash=hash,payloadHash="c".repeat(64))))
        return Fixture(journal,tokens,disk,RankingOperationGate(journal),vault,pending)
    }
    private fun client(f: Fixture, transport: AccountDeletionTransport) = RankingAccountDeletion(url,f.vault,f.pending,f.gate,transport)

    @Test fun `lost response keeps journal token and pending until same token gets receipt`() = runTest {
        val f=fixture(); var calls=0
        val client=client(f,AccountDeletionTransport { u,t ->
            assertEquals("https://rank.example/participants/me",u); assertEquals(token,t); calls++
            if (calls == 1) throw IllegalStateException() else DeletionResponse(204,"v1")
        })
        assertEquals(AccountDeletionResult.Uncertain,client.delete())
        assertEquals(DeletionJournal(accountHash=hash), f.gate.read())
        assertFalse(f.journal.data!!.toString(Charsets.UTF_8).contains(token))
        assertNotNull(f.pendingDisk.data); assertTrue(f.vault.load() is TokenLoad.Available)
        assertEquals("blocked",f.gate.unlessDeleting("blocked") { "should not run" })
        // Simulate a new gate and client after process restart.
        val reopened=RankingOperationGate(f.journal)
        val retry=RankingAccountDeletion(url,f.vault,f.pending,reopened,AccountDeletionTransport { _,t ->
            assertEquals(token,t); calls++; DeletionResponse(204,"v1")
        })
        assertEquals(AccountDeletionResult.Deleted,retry.delete()); assertEquals(2,calls)
        assertNull(f.journal.data); assertNull(f.pendingDisk.data); assertEquals(TokenLoad.Missing,f.vault.load())
        assertEquals("allowed",reopened.unlessDeleting("blocked") { "allowed" })
    }

    @Test fun `plain old server 204 or other status never clears credentials`() = runTest {
        for (reply in listOf(DeletionResponse(204,null),DeletionResponse(204,"v0"),DeletionResponse(401,null),DeletionResponse(503,null))) {
            val f=fixture()
            assertEquals(AccountDeletionResult.Uncertain,client(f,AccountDeletionTransport { _,_ -> reply }).delete())
            assertFalse(f.gate.read()!!.confirmed); assertTrue(f.vault.load() is TokenLoad.Available); assertNotNull(f.pendingDisk.data)
        }
    }

    @Test fun `local cleanup failure after confirmed receipt resumes without another network call`() = runTest {
        val f=fixture(); var calls=0; f.pendingDisk.failDelete=true
        val client=client(f,AccountDeletionTransport { _,_ -> calls++; DeletionResponse(204,"v1") })
        assertEquals(AccountDeletionResult.LocalCleanupIncomplete,client.delete())
        assertTrue(f.gate.read()!!.confirmed); assertNotNull(f.pendingDisk.data); assertTrue(f.vault.load() is TokenLoad.Available)
        f.pendingDisk.failDelete=false; f.tokens.failDelete=true
        assertEquals(AccountDeletionResult.LocalCleanupIncomplete,client.delete())
        assertNull(f.pendingDisk.data); assertTrue(f.gate.read()!!.confirmed)
        f.tokens.failDelete=false
        assertEquals(AccountDeletionResult.Deleted,client.delete())
        assertEquals(1,calls); assertNull(f.journal.data); assertEquals(TokenLoad.Missing,f.vault.load())
    }

    @Test fun `confirmed journal can finish after token already disappeared`() = runTest {
        val f=fixture(false); f.gate.save(DeletionJournal(accountHash=hash,confirmed=true))
        assertTrue(f.vault.clearMatchingAfterDeletion(hash)); var calls=0
        val client=client(f,AccountDeletionTransport { _,_ -> calls++; DeletionResponse(204,"v1") })
        assertEquals(AccountDeletionResult.Deleted,client.delete())
        assertNull(f.journal.data); assertEquals(0,calls)
    }

    @Test fun `unconfirmed journal with lost token remains blocked`() = runTest {
        val f=fixture(false); f.gate.save(DeletionJournal(accountHash=hash))
        assertTrue(f.vault.clearMatchingAfterDeletion(hash)); var calls=0
        val client=client(f,AccountDeletionTransport { _,_ -> calls++; DeletionResponse(204,"v1") })
        assertEquals(AccountDeletionResult.CredentialUnavailable,client.delete())
        assertNotNull(f.journal.data); assertEquals(0,calls)
        assertEquals("blocked",f.gate.unlessDeleting("blocked") { "unsafe" })
    }

    @Test fun `mismatched token or pending marker never erases another account`() = runTest {
        val f=fixture(); f.gate.save(DeletionJournal(accountHash="d".repeat(64),confirmed=true)); var calls=0
        val client=client(f,AccountDeletionTransport { _,_ -> calls++; DeletionResponse(204,"v1") })
        assertEquals(AccountDeletionResult.LocalCleanupIncomplete,client.delete())
        assertEquals(0,calls); assertTrue(f.vault.load() is TokenLoad.Available); assertNotNull(f.pendingDisk.data)
        assertTrue(f.gate.read()!!.confirmed)
    }

    @Test fun `corrupt journal or failed journal write blocks activity and no request is sent`() = runTest {
        val f=fixture(); var calls=0
        val client=client(f,AccountDeletionTransport { _,_ -> calls++; DeletionResponse(204,"v1") })
        f.journal.failWrite=true
        assertEquals(AccountDeletionResult.LocalCleanupIncomplete,client.delete()); assertEquals(0,calls)
        f.journal.failWrite=false; f.journal.data="broken".toByteArray()
        assertEquals(AccountDeletionResult.LocalCleanupIncomplete,client.delete())
        assertEquals("blocked",f.gate.unlessDeleting("blocked") { "allowed" }); assertEquals(0,calls)
        assertNotNull(f.pendingDisk.data); assertTrue(f.vault.load() is TokenLoad.Available)
    }

    @Test fun `in flight account activity and deletion are serialized`() = runTest {
        val f=fixture(false); val started=CompletableDeferred<Unit>(); val finish=CompletableDeferred<Unit>(); var deletes=0
        val ongoing=async { f.gate.unlessDeleting("blocked") { started.complete(Unit); finish.await(); "done" } }
        started.await()
        val deleting=async { client(f,AccountDeletionTransport { _,_ -> deletes++; DeletionResponse(503,null) }).delete() }
        assertEquals(0,deletes); finish.complete(Unit)
        assertEquals("done",ongoing.await()); assertEquals(AccountDeletionResult.Uncertain,deleting.await())
        assertEquals(1,deletes); assertEquals("blocked",f.gate.unlessDeleting("blocked") { "unsafe" })
    }

    @Test fun `cancellation retains unresolved journal`() = runTest {
        val f=fixture()
        try { client(f,AccountDeletionTransport { _,_ -> throw CancellationException() }).delete(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertFalse(f.gate.read()!!.confirmed); assertNotNull(f.pendingDisk.data); assertTrue(f.vault.load() is TokenLoad.Available)
    }
}
