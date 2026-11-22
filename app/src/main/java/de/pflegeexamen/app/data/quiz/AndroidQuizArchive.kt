package de.pflegeexamen.app.data.quiz

import android.content.Context
import android.util.AtomicFile
import de.pflegeexamen.app.data.content.PublishedContentStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex

object AndroidQuizArchive {
    private val lock = Mutex()
    fun create(context: Context): QuizArchiveClient = QuizArchiveClient(
        PublishedContentStore.CONTENT_URL, HttpsArchiveTransport(), FileArchiveCache(context.applicationContext), lock
    )
}

/** Private, size-limited, one-file-per-day cache; downloaded source content is not modified. */
internal class FileArchiveCache(context: Context) : ArchiveCache {
    private val directory = File(context.filesDir, "quiz-archive")
    private fun file(date: String): File {
        require(QuizArchiveCodec.validDate(date))
        return File(directory, "$date.json")
    }
    override fun dates(): List<String> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory)
        return (directory.listFiles() ?: error("Archiv nicht lesbar")).mapNotNull { file ->
            val match = Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2})\\.json(?:\\.bak|\\.new)?$").matchEntire(file.name)
            match?.groupValues?.get(1)?.takeIf(QuizArchiveCodec::validDate)
        }.distinct()
    }
    override fun read(date: String): ByteArray? {
        val base = file(date)
        check(!File(base.path + ".new").exists())
        val stream = try { AtomicFile(base).openRead() } catch (e: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists() || (directory.exists() && !directory.isDirectory)) throw e
            return null
        }
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= QuizArchiveCodec.MAX_DAY_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
    override fun writeNew(date: String, bytes: ByteArray) {
        require(bytes.size <= QuizArchiveCodec.MAX_DAY_BYTES)
        val base = file(date)
        check((directory.isDirectory || directory.mkdirs()) && !base.exists() &&
            !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
        val atomic = AtomicFile(base)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
