package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class RankingTokenVaultTest {
    private val token = "ab".repeat(32)
    private class MemoryStorage : EncryptedTokenStorage {
        var value: ByteArray? = null
        var failRead = false
        var failWrite = false
        var failDelete = false
        override fun read(): ByteArray? { if (failRead) throw IllegalStateException(); return value?.clone() }
        override fun write(bytes: ByteArray) { if (failWrite) throw IllegalStateException(); value = bytes.clone() }
        override fun delete() { if (failDelete) throw IllegalStateException(); value = null }
    }
    // For vault behavior only; production uses Android Keystore AES-GCM.
    private class FakeCipher : RankingTokenCipher {
        var failDecrypt = false
        override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(0x55) + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (failDecrypt) throw IllegalStateException()
            require(ciphertext.first() == 0x55.toByte())
            return ciphertext.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    @Test fun `new token is saved once and load is redacted`() = runTest {
        val storage = MemoryStorage(); val vault = RankingTokenVault(storage, FakeCipher())
        assertEquals(TokenLoad.Missing, vault.load())
        assertEquals(TokenSave.SAVED, vault.saveNew(token))
        assertFalse(storage.value!!.toString(Charsets.US_ASCII).contains(token))
        val loaded = vault.load() as TokenLoad.Available
        assertEquals(token, loaded.token)
        assertFalse(loaded.toString().contains(token))
        assertEquals(TokenSave.ALREADY_EXISTS, vault.saveNew("c".repeat(64)))
        assertEquals(token, (vault.load() as TokenLoad.Available).token)
    }

    @Test fun `invalid tokens and storage errors cannot overwrite a credential`() = runTest {
        val storage = MemoryStorage(); val cipher = FakeCipher(); val vault = RankingTokenVault(storage, cipher)
        for (bad in listOf("", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(64) + "\n"))
            assertEquals(TokenSave.INVALID_TOKEN, vault.saveNew(bad))
        assertNull(storage.value)
        storage.failRead = true
        assertEquals(TokenSave.UNAVAILABLE, vault.saveNew(token))
        assertEquals(TokenLoad.Unavailable, vault.load())
        storage.failRead = false; storage.failWrite = true
        assertEquals(TokenSave.UNAVAILABLE, vault.saveNew(token))
        assertNull(storage.value)
        storage.failWrite = false
        assertEquals(TokenSave.SAVED, vault.saveNew(token))
        val original = storage.value!!.clone()
        cipher.failDecrypt = true
        assertEquals(TokenLoad.Unavailable, vault.load())
        assertEquals(TokenSave.ALREADY_EXISTS, vault.saveNew("b".repeat(64)))
        assertArrayEquals(original, storage.value)
    }

    @Test fun `corrupt or oversized ciphertext remains untouched`() = runTest {
        val storage = MemoryStorage(); val vault = RankingTokenVault(storage, FakeCipher())
        for (bad in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(RankingTokenVault.MAX_ENCRYPTED_BYTES + 1))) {
            storage.value = bad.clone()
            assertEquals(TokenLoad.Unavailable, vault.load())
            assertEquals(TokenSave.ALREADY_EXISTS, vault.saveNew(token))
            assertArrayEquals(bad, storage.value)
        }
    }

    @Test fun `delete failure is visible and successful local clearing does not imply server deletion`() = runTest {
        val storage = MemoryStorage(); val vault = RankingTokenVault(storage, FakeCipher())
        assertEquals(TokenSave.SAVED, vault.saveNew(token))
        storage.failDelete = true
        assertFalse(vault.clearMatchingAfterDeletion(sha256(token.toByteArray(Charsets.US_ASCII))))
        assertEquals(token, (vault.load() as TokenLoad.Available).token)
        storage.failDelete = false
        assertFalse(vault.clearMatchingAfterDeletion("a".repeat(64)))
        assertTrue(vault.clearMatchingAfterDeletion(sha256(token.toByteArray(Charsets.US_ASCII))))
        assertEquals(TokenLoad.Missing, vault.load())
    }

    @Test fun `simultaneous saves do not replace each other`() = runTest {
        val storage = MemoryStorage(); val lock = Mutex()
        val vault = RankingTokenVault(storage, FakeCipher(), lock)
        val secondVault = RankingTokenVault(storage, FakeCipher(), lock)
        val results = listOf(async { vault.saveNew(token) }, async { secondVault.saveNew("c".repeat(64)) }).awaitAll()
        assertEquals(1, results.count { it == TokenSave.SAVED })
        assertEquals(1, results.count { it == TokenSave.ALREADY_EXISTS })
        assertTrue((vault.load() as TokenLoad.Available).token in listOf(token, "c".repeat(64)))
    }

    @Test fun `cancellation is propagated`() = runTest {
        val storage = object : EncryptedTokenStorage {
            override fun read(): ByteArray? = throw CancellationException()
            override fun write(bytes: ByteArray) = Unit
            override fun delete() = Unit
        }
        val vault = RankingTokenVault(storage, FakeCipher())
        try { vault.load(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
}
