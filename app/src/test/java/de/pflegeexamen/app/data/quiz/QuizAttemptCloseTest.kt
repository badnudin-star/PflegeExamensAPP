package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QuizAttemptCloseTest {
    private val url = "https://rank.example/leaderboard"
    private val token = "ab".repeat(32)
    private val date = "2026-11-13"
    private fun client(status: Int, body: String) = QuizAttemptClose(url, ClosureTransport { _,_,_ -> ClosureResponse(status,body.toByteArray()) })

    @Test fun `valid closed and recorded replies are distinct`() = runTest {
        assertEquals(AttemptClosure.Closed(date),client(200,"""{"date":"2026-11-13","status":"closed"}""").close(date,token))
        val hash="e".repeat(64)
        assertEquals(AttemptClosure.Recorded(date,12,50,hash),client(200,"""{"date":"2026-11-13","status":"recorded","correct":12,"total":50,"payloadHash":"$hash"}""").close(date,token))
        assertEquals(AttemptClosure.Recorded(date,12,50,null),client(200,"""{"date":"2026-11-13","status":"recorded","correct":12,"total":50,"payloadHash":null}""").close(date,token))
    }

    @Test fun `invalid response never confirms closure`() = runTest {
        for ((status,body) in listOf(
            404 to """{"date":"2026-11-13","status":"closed"}""", 200 to "{}",
            200 to """{"date":"2026-11-14","status":"closed"}""",
            200 to """{"date":"2026-11-13","status":"closed","correct":10}""",
            200 to """{"date":"2026-11-13","status":"unknown"}""",
            200 to """{"date":"2026-11-13","status":"recorded","correct":51,"total":50}""",
            200 to """{"date":"2026-11-13","status":"recorded","correct":1,"total":50,"payloadHash":"invalid"}""",
            200 to "x".repeat(4097))) {
            assertEquals(AttemptClosure.Unavailable,client(status,body).close(date,token))
        }
    }

    @Test fun `unsafe inputs never send and cancellation propagates`() = runTest {
        var calls=0
        val transport=ClosureTransport { _,_,_ -> calls++; throw CancellationException() }
        for (bad in listOf("", "http://rank.example/leaderboard", "https://u:p@rank.example/leaderboard", "https://rank.example/leaderboard?x=1"))
            assertEquals(AttemptClosure.Unavailable,QuizAttemptClose(bad,transport).close(date,token))
        assertEquals(AttemptClosure.Unavailable,QuizAttemptClose(url,transport).close("2026-02-30",token))
        assertEquals(AttemptClosure.Unavailable,QuizAttemptClose(url,transport).close(date,"invalid"))
        assertEquals(0,calls)
        try { QuizAttemptClose(url,transport).close(date,token); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
}
