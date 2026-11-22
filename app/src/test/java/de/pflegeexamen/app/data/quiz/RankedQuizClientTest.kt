package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RankedQuizClientTest {
    private val url = "https://ranking.example/leaderboard"
    private val date = "2026-11-13"
    private val fingerprint = "a".repeat(64)
    private fun question(index: Int) = buildJsonObject {
        put("id", "id-$index"); put("topic", "Pflege"); put("question", "Frage $index?")
        put("options", JsonArray(listOf("A", "B", "C", "D").map(::JsonPrimitive)))
        put("correctIndex", index % 4); put("explanation", "Erklärung $index")
    }
    private fun body(day: String = date, hash: String = fingerprint, questions: List<JsonElement> = (0 until 50).map(::question)) =
        buildJsonObject { put("date", day); put("fingerprint", hash); put("questions", JsonArray(questions)) }.toString()
    private fun client(raw: String, status: Int = 200) = RankedQuizClient(url, RankedQuizTransport { RankedQuizResponse(status, raw.toByteArray()) })
    private fun changed(q: JsonObject, key: String, value: JsonElement) = JsonObject(q + (key to value))

    @Test fun `loads public server quiz with date fingerprint and correct answers unchanged`() = runTest {
        var seen = ""; var calls = 0
        val loader = RankedQuizClient(url, RankedQuizTransport { u -> seen = u; calls++; RankedQuizResponse(200, body().toByteArray()) })
        val result = loader.load() as RankedQuizLoad.Ready
        assertEquals("https://ranking.example/quiz/today", seen)
        assertEquals(1, calls)
        assertEquals(date, result.quiz.date); assertEquals(fingerprint, result.quiz.fingerprint)
        assertEquals(50, result.quiz.questions.size)
        assertEquals("id-3", result.quiz.questions[3].id); assertEquals(3, result.quiz.questions[3].correctIndex)
        assertEquals("Erklärung 3", result.quiz.questions[3].explanation)
    }

    @Test fun `invalid configuration and responses never yield a quiz`() = runTest {
        var calls = 0
        val transport = RankedQuizTransport { calls++; RankedQuizResponse(200, body().toByteArray()) }
        assertEquals(RankedQuizLoad.NotConfigured, RankedQuizClient("", transport).load())
        for (bad in listOf("http://ranking.example/leaderboard", "https://user:pw@ranking.example/leaderboard", "https://ranking.example/leaderboard?x=1", "https://ranking.example/leaderboard#x", "https://ranking.example/quiz/today"))
            assertEquals(RankedQuizLoad.Unavailable, RankedQuizClient(bad, transport).load())
        assertEquals(0, calls)
        for (status in listOf(201, 301, 404, 429, 500, 503)) assertEquals(RankedQuizLoad.Unavailable, client(body(), status).load())
        for (raw in listOf("{}", "not json", body(day="2026-02-30"), body(hash="A".repeat(64)), body(hash="abc"), "x".repeat(RankedQuizClient.MAX_RESPONSE_BYTES + 1)))
            assertEquals(RankedQuizLoad.Unavailable, client(raw).load())
        val invalidUtf8 = RankedQuizClient(url, RankedQuizTransport { RankedQuizResponse(200, byteArrayOf(0xc3.toByte(), 0x28)) })
        assertEquals(RankedQuizLoad.Unavailable, invalidUtf8.load())
        assertEquals(RankedQuizLoad.Unavailable, RankedQuizClient(url, RankedQuizTransport { throw IllegalStateException() }).load())
        try { RankedQuizClient(url, RankedQuizTransport { throw CancellationException() }).load(); fail("cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun `rejects incomplete invalid or unsubmitable questions`() = runTest {
        val originals = (0 until 50).map(::question)
        val variants = listOf(
            changed(originals[0], "id", JsonPrimitive("")),
            changed(originals[0], "id", JsonPrimitive("x".repeat(201))),
            changed(originals[0], "id", JsonPrimitive("id\n1")),
            changed(originals[0], "topic", JsonPrimitive(" ")),
            changed(originals[0], "question", JsonPrimitive("")),
            changed(originals[0], "explanation", JsonPrimitive("")),
            changed(originals[0], "correctIndex", JsonPrimitive(4)),
            changed(originals[0], "options", JsonArray(listOf(JsonPrimitive("A")))),
            changed(originals[0], "options", JsonArray(listOf("A", "A", "C", "D").map(::JsonPrimitive))),
            changed(originals[0], "options", JsonArray(listOf("A", " ", "C", "D").map(::JsonPrimitive)))
        )
        assertEquals(RankedQuizLoad.Unavailable, client(body(questions=originals.drop(1))).load())
        assertEquals(RankedQuizLoad.Unavailable, client(body(questions=originals + originals[0])).load())
        assertEquals(RankedQuizLoad.Unavailable, client(body(questions=listOf(originals[1]) + originals.drop(1))).load())
        for (bad in variants) assertEquals(RankedQuizLoad.Unavailable, client(body(questions=listOf(bad) + originals.drop(1))).load())
    }

    @Test fun `http transport rejects unsafe destinations before connecting`() = runTest {
        val transport = HttpsRankedQuizTransport()
        for (bad in listOf("http://ranking.example/quiz/today", "https://user:pw@ranking.example/quiz/today", "https://ranking.example/quiz/today?x=1", "https://ranking.example/quiz/today#x", "https://ranking.example/leaderboard")) {
            try { transport.fetch(bad); fail("unsafe URL must be rejected") } catch (_: IllegalArgumentException) { }
        }
    }
}
