package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class QuizAttemptSubmitTest {
    private val url = "https://rank.example/leaderboard"
    private val token = "ab".repeat(32)
    private val date = "2026-11-13"
    private val fingerprint = "c".repeat(64)
    private val answers = (1..50).map { RankedAnswer("question-$it", it % 4) }
    private class Storage : EncryptedTokenStorage {
        var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun write(bytes: ByteArray) { value = bytes.clone() }
        override fun delete() { value = null }
    }
    private class PendingStorage : PendingAttemptPersistence {
        private var data: ByteArray? = null
        override fun read(): ByteArray? = data?.clone()
        override fun write(data: ByteArray) { this.data = data.clone() }
        override fun delete() { data = null }
    }
    private fun pending() = PendingAttemptStore(PendingStorage())
    private class Cipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    private fun response(code: Int, body: String = "") = SubmissionResponse(code, body.toByteArray())
    private suspend fun vault(): RankingTokenVault = RankingTokenVault(Storage(), Cipher()).also { assertEquals(TokenSave.SAVED, it.saveNew(token)) }

    @Test fun `sends only selections once and accepts server score`() = runTest {
        val vault = vault(); var calls = 0
        val transport = QuizSubmissionTransport { endpoint, credential, data ->
            calls++
            assertEquals("https://rank.example/quiz/attempt", endpoint)
            assertEquals(token, credential)
            val text = data.toString(Charsets.UTF_8)
            assertFalse(text.contains(token))
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("date", "fingerprint", "answers"), body.keys)
            assertEquals(date, body["date"]!!.jsonPrimitive.content)
            assertEquals(fingerprint, body["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(50, body["answers"]!!.jsonArray.size)
            assertTrue(body["answers"]!!.jsonArray.all { it.jsonObject.keys == setOf("id", "option") })
            response(201, """{"date":"2026-11-13","correct":41,"total":50}""")
        }
        assertEquals(SubmissionResult.Recorded(date, 41, 50), QuizAttemptSubmit(url, vault, pending(), transport).submit(date, fingerprint, answers))
        assertEquals(1, calls)
    }

    @Test fun `invalid input or missing account never sends`() = runTest {
        val storage = Storage(); val empty = RankingTokenVault(storage, Cipher()); var calls = 0
        val transport = QuizSubmissionTransport { _,_,_ -> calls++; response(500) }
        assertEquals(SubmissionResult.NotConfigured, QuizAttemptSubmit("", empty, pending(), transport).submit(date, fingerprint, answers))
        for (bad in listOf("http://rank.example/leaderboard", "https://user:pass@rank.example/leaderboard", "https://rank.example/leaderboard?x=1"))
            assertEquals(SubmissionResult.InvalidInput, QuizAttemptSubmit(bad, empty, pending(), transport).submit(date, fingerprint, answers))
        val client = QuizAttemptSubmit(url, empty, pending(), transport)
        assertEquals(SubmissionResult.InvalidInput, client.submit("2026-02-30", fingerprint, answers))
        assertEquals(SubmissionResult.InvalidInput, client.submit(date, "X".repeat(64), answers))
        assertEquals(SubmissionResult.InvalidInput, client.submit(date, fingerprint, answers.drop(1)))
        assertEquals(SubmissionResult.InvalidInput, client.submit(date, fingerprint, answers.dropLast(1) + answers.first()))
        assertEquals(SubmissionResult.InvalidInput, client.submit(date, fingerprint, answers.mapIndexed { i, a -> if(i == 0) a.copy(option=4) else a }))
        assertEquals(SubmissionResult.InvalidInput, client.submit(date, fingerprint, answers.mapIndexed { i, a -> if(i == 0) a.copy(id="x\n") else a }))
        assertEquals(SubmissionResult.NoAccount, client.submit(date, fingerprint, answers))
        storage.value = byteArrayOf(1,2,3)
        assertEquals(SubmissionResult.CredentialUnavailable, client.submit(date, fingerprint, answers))
        assertEquals(0, calls)
    }

    @Test fun `network ambiguity and conflicts do not retry or fabricate a score`() = runTest {
        val vault = vault()
        for ((code, body, expected) in listOf(
            Triple(401,"",SubmissionResult.CredentialRejected), Triple(409,"",SubmissionResult.Conflict),
            Triple(429,"",SubmissionResult.Uncertain), Triple(503,"",SubmissionResult.Uncertain),
            Triple(201,"""{"date":"2026-11-14","correct":10,"total":50}""",SubmissionResult.Uncertain),
            Triple(201,"""{"date":"2026-11-13","correct":51,"total":50}""",SubmissionResult.Uncertain),
            Triple(201,"x".repeat(4097),SubmissionResult.Uncertain))) {
            var calls = 0
            val client = QuizAttemptSubmit(url, vault, pending(), QuizSubmissionTransport { _,_,_ -> calls++; response(code,body) })
            assertEquals(expected, client.submit(date, fingerprint, answers))
            assertEquals(1,calls)
        }
        var calls = 0
        val lost = QuizAttemptSubmit(url, vault, pending(), QuizSubmissionTransport { _,_,_ -> calls++; throw IllegalStateException() })
        assertEquals(SubmissionResult.Uncertain, lost.submit(date, fingerprint, answers))
        assertEquals(1,calls)
        val cancelled = QuizAttemptSubmit(url, vault, pending(), QuizSubmissionTransport { _,_,_ -> throw CancellationException() })
        try { cancelled.submit(date, fingerprint, answers); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun `http transport rejects unsafe destinations before network`() = runTest {
        val transport = HttpsQuizSubmissionTransport()
        for ((endpoint, credential) in listOf(
            "http://rank.example/quiz/attempt" to token,
            "https://user:pw@rank.example/quiz/attempt" to token,
            "https://rank.example/quiz/attempt?x=1" to token,
            "https://rank.example/quiz/attempt" to "bad")) {
            try { transport.send(endpoint, credential, byteArrayOf()); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
