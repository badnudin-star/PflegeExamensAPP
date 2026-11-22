package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RankedQuizStartTest {
    private val token = "a".repeat(64)
    private val date = "2026-11-13"
    private val quiz = RankedQuiz(date, "b".repeat(64), emptyList())
    private class Memory : EncryptedTokenStorage {
        var data: ByteArray? = null
        override fun read() = data
        override fun write(bytes: ByteArray) { data = bytes }
        override fun delete() { data = null }
    }
    private class Cipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.clone()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.clone()
    }
    private suspend fun vault(): RankingTokenVault = RankingTokenVault(Memory(), Cipher()).also { assertEquals(TokenSave.SAVED, it.saveNew(token)) }

    @Test fun `pending or unavailable marker blocks before quiz load`() = runTest {
        var loads = 0; var lookups = 0
        val vault = vault()
        for (state in listOf(PendingQuizState.StorageUnavailable, PendingQuizState.CredentialMismatch,
            PendingQuizState.Waiting(date), PendingQuizState.Recorded(date, 3), PendingQuizState.DifferentOrUnverifiedAttempt(date))) {
            val checker = RankedQuizStartCheck(StartPendingCheck { state }, StartQuizLoader { loads++; RankedQuizLoad.Ready(quiz) }, vault,
                StartResultLookup { _,_ -> lookups++; AttemptLookup.NotFound })
            assertEquals(RankedQuizStart.Pending, checker.start())
        }
        assertEquals(0, loads); assertEquals(0, lookups)
    }

    @Test fun `server result blocks duplicate and only a not found response permits start`() = runTest {
        val vault = vault(); var seen = ""
        fun checker(result: AttemptLookup) = RankedQuizStartCheck(StartPendingCheck { PendingQuizState.None },
            StartQuizLoader { RankedQuizLoad.Ready(quiz) }, vault, StartResultLookup { d,t -> seen = "$d:$t"; result })
        assertEquals(RankedQuizStart.Ready(quiz), checker(AttemptLookup.NotFound).start())
        assertEquals("$date:$token", seen)
        assertEquals(RankedQuizStart.AlreadyRecorded(date, 20, 50), checker(AttemptLookup.Found(date,20,50,null)).start())
        assertEquals(RankedQuizStart.Closed(date), checker(AttemptLookup.Closed(date)).start())
        for (result in listOf(AttemptLookup.Unavailable, AttemptLookup.InvalidToken, AttemptLookup.NotConfigured,
            AttemptLookup.Found("2026-11-14",20,50,null), AttemptLookup.Found(date,51,50,null)))
            assertEquals(RankedQuizStart.Unavailable, checker(result).start())
    }

    @Test fun `failures fail closed and cancellation propagates`() = runTest {
        val vault = vault()
        val failed = RankedQuizStartCheck(StartPendingCheck { PendingQuizState.None },
            StartQuizLoader { throw IllegalStateException() }, vault, StartResultLookup { _,_ -> AttemptLookup.NotFound })
        assertEquals(RankedQuizStart.Unavailable, failed.start())
        val cancelled = RankedQuizStartCheck(StartPendingCheck { throw CancellationException() },
            StartQuizLoader { RankedQuizLoad.Ready(quiz) }, vault, StartResultLookup { _,_ -> AttemptLookup.NotFound })
        try { cancelled.start(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
}
