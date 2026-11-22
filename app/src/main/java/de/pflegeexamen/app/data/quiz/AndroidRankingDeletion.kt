package de.pflegeexamen.app.data.quiz

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Every app participation/deletion operation in this process must use this shared gate. */
object AndroidRankingOperationGate {
    private var instance: RankingOperationGate? = null
    @Synchronized fun get(context: Context): RankingOperationGate = instance ?: RankingOperationGate(
        NoBackupDeletionJournal(context.applicationContext)
    ).also { instance = it }
}

internal class NoBackupDeletionJournal(context: Context) : DeletionJournalPersistence {
    private val base = File(context.noBackupFilesDir, "quiz-ranking-deletion.json")
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
                require(total <= 1024)
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
