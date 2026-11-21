package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QuizRegistrationTest {
    private val url = "https://rank.example/leaderboard"
    private val token = "ab".repeat(32)
    private class Storage : EncryptedTokenStorage {
        var value: ByteArray? = null
        var failWrite = false
        var failRead = false
        override fun read(): ByteArray? { if (failRead) throw IllegalStateException(); return value?.clone() }
        override fun write(bytes: ByteArray) { if (failWrite) throw IllegalStateException(); value = bytes.clone() }
        override fun delete() { value = null }
    }
    // This fake is not cryptography. Production uses Android Keystore.
    private class FakeCipher : RankingTokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray) = ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    private fun response(code: Int, body: String = "") = RegistrationResponse(code, body.toByteArray())

    @Test fun `token is safely saved before registration and lost response is recoverable`() = runTest {
        val storage = Storage(); val vault = RankingTokenVault(storage, FakeCipher())
        var registered: String? = null
        var sentToken = ""
        var loseResponse = true
        val transport = RegistrationTransport { method, endpoint, credential, body ->
            assertEquals(token, (vault.load() as TokenLoad.Available).token)
            sentToken = credential
            when (method) {
                "POST" -> {
                    assertEquals("https://rank.example/participants", endpoint)
                    assertEquals("""{"username":"Alice"}""", body!!.toString(Charsets.UTF_8))
                    if (registered == null) registered = "Alice"
                    if (loseResponse) { loseResponse = false; throw IllegalStateException("lost response") }
                    response(200, """{"username":"Alice"}""")
                }
                else -> {
                    assertEquals("https://rank.example/participants/me", endpoint)
                    assertNull(body)
                    response(200, """{"username":"Alice"}""")
                }
            }
        }
        val client = QuizRegistration(url, vault, RankingTokenGenerator { token }, transport)
        assertEquals(RegistrationState.Uncertain, client.register("Alice"))
        assertEquals(token, sentToken)
        assertFalse(storage.value!!.toString(Charsets.US_ASCII).contains(token))
        // Simulate constructing a new client after process restart.
        val restarted = QuizRegistration(url, vault, RankingTokenGenerator { fail("must not generate another token"); "" }, transport)
        assertEquals(RegistrationState.Registered("Alice"), restarted.status())
        assertEquals(RegistrationState.Registered("Alice"), restarted.register("Alice"))
        assertEquals(token, (vault.load() as TokenLoad.Available).token)
    }

    @Test fun `failed storage and invalid configuration never contact server`() = runTest {
        val storage = Storage(); val vault = RankingTokenVault(storage, FakeCipher()); var calls=0
        val transport = RegistrationTransport { _,_,_,_ -> calls++; response(500) }
        val make = { endpoint: String -> QuizRegistration(endpoint, vault, RankingTokenGenerator { token }, transport) }
        assertEquals(RegistrationState.NotConfigured, make("").register("Alice"))
        for (bad in listOf("http://rank.example/leaderboard", "https://user:pw@rank.example/leaderboard", "https://rank.example/leaderboard?x=1"))
            assertEquals(RegistrationState.Uncertain, make(bad).register("Alice"))
        for (bad in listOf("A", " Alice", "Alice  Bob", "Bad!", "x".repeat(25)))
            assertEquals(RegistrationState.InvalidName, make(url).register(bad))
        assertEquals(RegistrationState.NotPrepared, make(url).status())
        storage.failWrite=true
        assertEquals(RegistrationState.StorageUnavailable, make(url).register("Alice"))
        assertEquals(0,calls)
        assertNull(storage.value)
    }

    @Test fun `existing corrupt token is never replaced or sent`() = runTest {
        val storage=Storage(); storage.value=byteArrayOf(1,2,3)
        val vault=RankingTokenVault(storage, FakeCipher()); var calls=0
        val client=QuizRegistration(url,vault,RankingTokenGenerator { token },RegistrationTransport { _,_,_,_ -> calls++; response(500) })
        assertEquals(RegistrationState.StorageUnavailable, client.register("Alice"))
        assertEquals(RegistrationState.StorageUnavailable, client.status())
        assertArrayEquals(byteArrayOf(1,2,3),storage.value)
        assertEquals(0,calls)
    }

    @Test fun `conflict and malformed status do not erase local token`() = runTest {
        val storage=Storage(); val vault=RankingTokenVault(storage, FakeCipher())
        assertEquals(TokenSave.SAVED,vault.saveNew(token))
        for ((code, body, expected) in listOf(
            Triple(409,"",RegistrationState.NameConflict), Triple(201,"{}",RegistrationState.Uncertain),
            Triple(201,"""{"username":"Other"}""",RegistrationState.Uncertain), Triple(429,"",RegistrationState.Uncertain),
            Triple(200,"x".repeat(4097),RegistrationState.Uncertain))) {
            val client=QuizRegistration(url,vault,RankingTokenGenerator { token },RegistrationTransport { _,_,_,_ -> response(code,body) })
            assertEquals(expected,client.register("Alice"))
            assertEquals(token,(vault.load() as TokenLoad.Available).token)
        }
        val absent=QuizRegistration(url,vault,RankingTokenGenerator { token },RegistrationTransport { _,_,_,_ -> response(404) })
        assertEquals(RegistrationState.NotRegistered, absent.status())
        val failed=QuizRegistration(url,vault,RankingTokenGenerator { token },RegistrationTransport { _,_,_,_ -> throw IllegalStateException() })
        assertEquals(RegistrationState.Uncertain, failed.status())
        val cancelled=QuizRegistration(url,vault,RankingTokenGenerator { token },RegistrationTransport { _,_,_,_ -> throw CancellationException() })
        try { cancelled.status(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun `secure generator has expected format and transport rejects unsafe destinations`() = runTest {
        val generated=SecureRankingTokenGenerator().create()
        assertTrue(Regex("^[0-9a-f]{64}$").matches(generated))
        val transport=HttpsRegistrationTransport()
        for ((method,endpoint,credential,body) in listOf(
            listOf("POST","http://rank.example/participants",token,"{}"),
            listOf("POST","https://user:pw@rank.example/participants",token,"{}"),
            listOf("POST","https://rank.example/participants?extra=1",token,"{}"),
            listOf("GET","https://rank.example/participants/me","bad",""))) {
            try { transport.send(method,endpoint,credential,if(method=="POST") body.toByteArray() else null); fail("Expected rejection before network") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
