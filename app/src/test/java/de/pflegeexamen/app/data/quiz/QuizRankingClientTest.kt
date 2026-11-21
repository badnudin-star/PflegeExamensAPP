package de.pflegeexamen.app.data.quiz

import de.pflegeexamen.app.domain.quiz.QuizRankingSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QuizRankingClientTest {
    @Test fun `disabled and unsafe endpoints never call network`() = runTest {
        var calls = 0
        val transport = PublicRankingTransport { calls++; byteArrayOf() }
        assertEquals(RankingLoad.NotConfigured, QuizRankingClient("", transport).load())
        for (url in listOf("http://example.org/leaderboard", "https://example.org/other", "https://user:pass@example.org/leaderboard",
                "https://example.org/leaderboard?x=1", "https://example.org/leaderboard#x", "https://example.org/leaderboard/")) {
            assertEquals(RankingLoad.Unavailable, QuizRankingClient(url, transport).load())
        }
        assertEquals(0, calls)
    }

    @Test fun `valid snapshot and failures produce explicit states`() = runTest {
        val expected = QuizRankingSnapshot(1_700_000_000_000, 1_700_000_001_000, 0, emptyList())
        val url = "https://example.org/leaderboard"
        var called = ""
        val client = QuizRankingClient(url, PublicRankingTransport { called = it; Json.encodeToString(expected).toByteArray() })
        assertEquals(RankingLoad.Ready(expected), client.load())
        assertEquals(url, called)
        assertEquals(RankingLoad.Unavailable, QuizRankingClient(url, PublicRankingTransport { null }).load())
        assertEquals(RankingLoad.Unavailable, QuizRankingClient(url, PublicRankingTransport { "bad".toByteArray() }).load())
        assertEquals(RankingLoad.Unavailable, QuizRankingClient(url, PublicRankingTransport { throw IllegalStateException() }).load())
        try {
            QuizRankingClient(url, PublicRankingTransport { throw CancellationException() }).load()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
