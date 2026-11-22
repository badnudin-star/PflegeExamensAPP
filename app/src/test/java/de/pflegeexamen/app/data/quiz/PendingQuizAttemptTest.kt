package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PendingQuizAttemptTest {
    private val token = "ab".repeat(32)
    private val date = "2026-11-13"
    private val fingerprint = "c".repeat(64)
    private val answers = (1..50).map { RankedAnswer("question-$it", it % 4) }
    private class PendingMemory : PendingAttemptPersistence {
        var data: ByteArray? = null
        var writes = 0
        var failWrite = 0
        var failRead = false
        var failDelete = false
        override fun read(): ByteArray? { if (failRead) throw IllegalStateException(); return data?.clone() }
        override fun write(data: ByteArray) { writes++; if (writes == failWrite) throw IllegalStateException(); this.data = data.clone() }
        override fun delete() { if (failDelete) throw IllegalStateException(); data = null }
    }
    private class TokenMemory : EncryptedTokenStorage {
        var data: ByteArray? = null
        override fun read() = data?.clone()
        override fun write(bytes: ByteArray) { data = bytes.clone() }
        override fun delete() { data = null }
    }
    private class Cipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    private suspend fun vault(value: String = token): RankingTokenVault = RankingTokenVault(TokenMemory(), Cipher()).also {
        assertEquals(TokenSave.SAVED, it.saveNew(value))
    }
    private fun client(vault: RankingTokenVault, pending: PendingAttemptStore, transport: QuizSubmissionTransport) =
        QuizAttemptSubmit("https://rank.example/leaderboard", vault, pending, transport)
    private fun recorded(correct: Int) = SubmissionResponse(201, """{"date":"2026-11-13","correct":$correct,"total":50}""".toByteArray())

    @Test fun `lost response remains blocked across restart until server result confirmed`() = runTest {
        val disk = PendingMemory(); val vault = vault(); var sends = 0
        val first = client(vault, PendingAttemptStore(disk), QuizSubmissionTransport { _,_,_ ->
            sends++
            val stored = disk.data!!.toString(Charsets.UTF_8)
            assertFalse(stored.contains(token)); assertFalse(stored.contains("question-")); assertFalse(stored.contains("option"))
            throw IllegalStateException("response lost after possible commit")
        })
        assertEquals(SubmissionResult.Uncertain, first.submit(date, fingerprint, answers))
        val restartedStore = PendingAttemptStore(disk)
        val second = client(vault, restartedStore, QuizSubmissionTransport { _,_,_ -> sends++; recorded(40) })
        assertEquals(SubmissionResult.AnotherAttemptPending, second.submit(date, fingerprint, answers.map { it.copy(option=0) }))
        assertEquals(1, sends)
        val notFound = PendingQuizReconciliation(restartedStore, vault, PendingResultLookup { _,_ -> AttemptLookup.NotFound })
        assertEquals(PendingQuizState.Waiting(date), notFound.reconcile())
        assertFalse(notFound.acknowledgeRecorded())
        val recover = PendingQuizReconciliation(restartedStore, vault, PendingResultLookup { d,t ->
            assertEquals(date,d); assertEquals(token,t); AttemptLookup.Found(date,40,50)
        })
        assertEquals(PendingQuizState.Recorded(date,40), recover.reconcile())
        val again = PendingQuizReconciliation(PendingAttemptStore(disk), vault, PendingResultLookup { _,_ -> fail("recorded result is local"); AttemptLookup.Unavailable })
        assertEquals(PendingQuizState.Recorded(date,40), again.reconcile())
        assertTrue(again.acknowledgeRecorded())
        assertEquals(PendingQuizState.None, again.reconcile())
    }

    @Test fun `storage failure or corrupt marker prevents sending and never overwrites`() = runTest {
        val disk=PendingMemory(); val vault=vault(); var sends=0
        val store=PendingAttemptStore(disk)
        val submitter=client(vault, store, QuizSubmissionTransport { _,_,_ -> sends++; recorded(1) })
        disk.failWrite=1
        assertEquals(SubmissionResult.PendingStorageUnavailable,submitter.submit(date,fingerprint,answers))
        assertEquals(0,sends)
        disk.failWrite=0; disk.data="not json".toByteArray()
        val original=disk.data!!.clone()
        assertEquals(SubmissionResult.PendingStorageUnavailable,submitter.submit(date,fingerprint,answers))
        assertArrayEquals(original,disk.data)
        assertEquals(PendingQuizState.StorageUnavailable,PendingQuizReconciliation(store,vault,PendingResultLookup { _,_ -> AttemptLookup.NotFound }).reconcile())
        assertEquals(0,sends)
    }

    @Test fun `failure saving a returned score keeps the marker for later reconciliation`() = runTest {
        val disk=PendingMemory(); disk.failWrite=2
        val store=PendingAttemptStore(disk); val vault=vault()
        val client=client(vault,store,QuizSubmissionTransport { _,_,_ -> recorded(42) })
        assertEquals(SubmissionResult.Uncertain,client.submit(date,fingerprint,answers))
        assertNotNull(disk.data)
        disk.failWrite=0
        val recovery=PendingQuizReconciliation(store,vault,PendingResultLookup { _,_ -> AttemptLookup.Found(date,42,50) })
        assertEquals(PendingQuizState.Recorded(date,42),recovery.reconcile())
    }

    @Test fun `different account or invalid server response never clears pending`() = runTest {
        val disk=PendingMemory(); val first=vault(); val store=PendingAttemptStore(disk)
        val submitter=client(first,store,QuizSubmissionTransport { _,_,_ -> throw IllegalStateException() })
        assertEquals(SubmissionResult.Uncertain,submitter.submit(date,fingerprint,answers))
        val other=vault("d".repeat(64)); var calls=0
        val mismatch=PendingQuizReconciliation(store,other,PendingResultLookup { _,_ -> calls++; AttemptLookup.Found(date,10,50) })
        assertEquals(PendingQuizState.CredentialMismatch,mismatch.reconcile())
        assertFalse(mismatch.acknowledgeRecorded()); assertEquals(0,calls)
        val wrongDate=PendingQuizReconciliation(store,first,PendingResultLookup { _,_ -> AttemptLookup.Found("2026-11-14",10,50) })
        assertEquals(PendingQuizState.Waiting(date),wrongDate.reconcile())
        assertFalse(wrongDate.acknowledgeRecorded())
    }

    @Test fun `two concurrent submitters sharing file cannot both send`() = runTest {
        val disk=PendingMemory(); val lock=Mutex(); val vault=vault()
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var sends=0
        val transport=QuizSubmissionTransport { _,_,_ -> sends++; started.complete(Unit); release.await(); recorded(10) }
        val first=client(vault,PendingAttemptStore(disk,lock),transport)
        val second=client(vault,PendingAttemptStore(disk,lock),transport)
        val job=async { first.submit(date,fingerprint,answers) }
        started.await()
        assertEquals(SubmissionResult.AnotherAttemptPending,second.submit(date,fingerprint,answers))
        assertEquals(1,sends)
        release.complete(Unit)
        assertEquals(SubmissionResult.Recorded(date,10,50),job.await())
    }

    @Test fun `cancellation after marker is stored does not discard it`() = runTest {
        val disk=PendingMemory(); val vault=vault(); val store=PendingAttemptStore(disk)
        val submitter=client(vault,store,QuizSubmissionTransport { _,_,_ -> throw CancellationException() })
        try { submitter.submit(date,fingerprint,answers); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        val recovery=PendingQuizReconciliation(PendingAttemptStore(disk),vault,PendingResultLookup { _,_ -> AttemptLookup.NotFound })
        assertEquals(PendingQuizState.Waiting(date),recovery.reconcile())
    }
}
