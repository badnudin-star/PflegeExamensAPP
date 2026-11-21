package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QuizAttemptLookupTest {
    private val url = "https://ranking.example/leaderboard"
    private val token = "a".repeat(64)
    private val date = "2026-11-13"
    private fun response(status: Int, value: String) = AttemptResponse(status, value.toByteArray())

    @Test fun `recovers a recorded result using only expected endpoint and supplied token`() = runTest {
        var capturedUrl = ""
        var capturedToken = ""
        val client = QuizAttemptLookup(url, AttemptLookupTransport { u, t ->
            capturedUrl = u; capturedToken = t
            response(200, """{"date":"2026-11-13","correct":38,"total":50}""")
        })
        assertEquals(AttemptLookup.Found(date, 38, 50), client.lookup(date, token))
        assertEquals("https://ranking.example/quiz/attempt/result?date=2026-11-13", capturedUrl)
        assertEquals(token, capturedToken)
        assertFalse(capturedUrl.contains(token))
    }

    @Test fun `not found is different from network failure and invalid token`() = runTest {
        assertEquals(AttemptLookup.NotFound, QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> response(404, """{"date":"2026-11-13","error":"Noch kein gewerteter Versuch."}""") }).lookup(date, token))
        assertEquals(AttemptLookup.InvalidToken, QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> response(401, "") }).lookup(date, token))
        for (code in listOf(300, 302, 400, 403, 429, 500, 503)) {
            assertEquals(AttemptLookup.Unavailable, QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> response(code, "") }).lookup(date, token))
        }
    }

    @Test fun `unsafe inputs do not contact server`() = runTest {
        var calls = 0
        val transport = AttemptLookupTransport { _,_ -> calls++; response(500, "") }
        assertEquals(AttemptLookup.NotConfigured, QuizAttemptLookup("", transport).lookup(date, token))
        for (badUrl in listOf("http://ranking.example/leaderboard", "https://user:pw@ranking.example/leaderboard", "https://ranking.example/leaderboard?x=1", "https://ranking.example/other"))
            assertEquals(AttemptLookup.Unavailable, QuizAttemptLookup(badUrl, transport).lookup(date, token))
        for (badDate in listOf("2026-02-30", "20261113", "2026-11-13&x=1", ""))
            assertEquals(AttemptLookup.Unavailable, QuizAttemptLookup(url, transport).lookup(badDate, token))
        assertEquals(AttemptLookup.InvalidToken, QuizAttemptLookup(url, transport).lookup(date, "secret"))
        assertEquals(0, calls)
    }

    @Test fun `http transport rejects unsafe destinations and invalid token before connecting`() = runTest {
        val transport = HttpsAttemptLookupTransport()
        for ((badUrl, badToken) in listOf(
            "http://ranking.example/quiz/attempt/result?date=$date" to token,
            "https://user:pw@ranking.example/quiz/attempt/result?date=$date" to token,
            "https://ranking.example/other?date=$date" to token,
            "https://ranking.example/quiz/attempt/result?date=$date&x=1" to token,
            "https://ranking.example/quiz/attempt/result?date=$date" to "not-a-token")) {
            try { transport.fetch(badUrl, badToken); fail("Should reject input before network") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `malformed wrong day excessive body or score are unavailable`() = runTest {
        for ((code, body) in listOf(
            200 to "{}", 200 to """{"date":"2026-11-14","correct":3,"total":50}""",
            404 to """{"date":"2026-11-14"}""", 200 to """{"date":"2026-11-13","correct":51,"total":50}""",
            200 to """{"date":"2026-11-13","correct":1,"total":49}""", 200 to "x".repeat(4097))) {
            assertEquals(AttemptLookup.Unavailable, QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> response(code, body) }).lookup(date, token))
        }
        assertEquals(AttemptLookup.Unavailable, QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> throw IllegalStateException() }).lookup(date, token))
        try {
            QuizAttemptLookup(url, AttemptLookupTransport { _,_ -> throw CancellationException() }).lookup(date, token)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
