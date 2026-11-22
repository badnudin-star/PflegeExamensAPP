package de.pflegeexamen.app.data.quiz

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Factory is not used until ranked participation is explicitly implemented. */
object AndroidRankingTokenVault {
    private val lock = Mutex() // All vault instances in this app process share the same file.
    fun create(context: Context): RankingTokenVault {
        val app = context.applicationContext
        return RankingTokenVault(NoBackupTokenStorage(app), KeystoreTokenCipher(app.packageName), lock)
    }
}

internal class NoBackupTokenStorage(context: Context) : EncryptedTokenStorage {
    private val base = File(context.noBackupFilesDir, "quiz-ranking-token.bin")
    private val atomic = AtomicFile(base)

    override fun read(): ByteArray? {
        val stream = try { atomic.openRead() } catch (e: FileNotFoundException) {
            // A partial/backup file might contain the only surviving credential.
            if (!base.parentFile.isDirectory || base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()) throw e
            return null
        }
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(256)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= RankingTokenVault.MAX_ENCRYPTED_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    override fun delete() {
        atomic.delete()
        check(!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
    }
}

internal class KeystoreTokenCipher(packageName: String, private val alias: String = "quiz-ranking-token-v1") : RankingTokenCipher {
    private val aad = (packageName + ":quiz-ranking-token:v1").toByteArray(Charsets.UTF_8)
    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey(store: KeyStore): SecretKey? = store.getKey(alias, null)?.let {
        it as? SecretKey ?: throw IllegalStateException("Unexpected key type")
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val store = keys()
        val key = existingKey(store) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == 1 + 12 + 64 + 16 && ciphertext[0] == 1.toByte())
        // Never create a new key for existing ciphertext: that would hide key loss.
        val key = existingKey(keys()) ?: throw IllegalStateException("Key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, 13, ciphertext.size - 13)
    }
}
