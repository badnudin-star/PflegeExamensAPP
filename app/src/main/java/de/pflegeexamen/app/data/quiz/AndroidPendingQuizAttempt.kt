package de.pflegeexamen.app.data.quiz

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileNotFoundException

/** Not used until the scored-quiz flow is explicitly integrated. */
object AndroidPendingQuizAttempt {
    private val lock = Mutex()
    fun create(context: Context): PendingAttemptStore = PendingAttemptStore(NoBackupPendingStorage(context.applicationContext), lock)
}

private class NoBackupPendingStorage(context: Context) : PendingAttemptPersistence {
    private val base = File(context.noBackupFilesDir, "quiz-ranking-pending.json")
    private val atomic = AtomicFile(base)
    override fun read(): ByteArray? {
        val stream = try { atomic.openRead() } catch (e: FileNotFoundException) {
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
                require(total <= PendingAttemptStore.MAX_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
    override fun write(data: ByteArray) {
        val output = atomic.startWrite()
        try { output.write(data); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    override fun delete() {
        atomic.delete()
        check(!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
    }
}
