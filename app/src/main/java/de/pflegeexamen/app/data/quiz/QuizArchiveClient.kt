package de.pflegeexamen.app.data.quiz

import de.pflegeexamen.app.domain.content.LearningCatalog
import de.pflegeexamen.app.domain.content.LearningCatalogCodec
import de.pflegeexamen.app.domain.content.PublishedQuiz
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArchiveDay(val date: String, val quizzes: List<PublishedQuiz>)
@Serializable
data class ArchiveDaySummary(val date: String, val questions: Int)
@Serializable
data class ArchivePage(val days: List<ArchiveDaySummary>, val nextBefore: String? = null)

sealed interface ArchivePageResult {
    data class Ready(val page: ArchivePage) : ArchivePageResult
    data object NotConfigured : ArchivePageResult
    data object Unavailable : ArchivePageResult
}
sealed interface ArchiveDayResult {
    data class Ready(val day: ArchiveDay, val fromCache: Boolean) : ArchiveDayResult
    data object NotConfigured : ArchiveDayResult
    data object Unavailable : ArchiveDayResult
    /** Existing data was not replaced; retrying must not silently overwrite it. */
    data object StorageError : ArchiveDayResult
}

interface ArchiveCache {
    fun dates(): List<String>
    /** Null means no file exists; damaged or inaccessible files must throw. */
    fun read(date: String): ByteArray?
    /** Create only if absent. Never overwrite an existing or partial file. */
    fun writeNew(date: String, bytes: ByteArray)
}
fun interface ArchiveTransport { suspend fun fetch(url: String, limit: Int): ByteArray? }

object QuizArchiveCodec {
    const val MAX_DAY_BYTES = 1024 * 1024
    const val MAX_PAGE_BYTES = 16 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    fun validDate(value: String): Boolean = runCatching {
        Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value) && LocalDate.parse(value).toString() == value
    }.getOrDefault(false)
    private fun text(bytes: ByteArray, limit: Int): String? = if (bytes.size > limit) null else
        runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()

    fun day(bytes: ByteArray, date: String): ArchiveDay? {
        if (!validDate(date)) return null
        val raw = text(bytes, MAX_DAY_BYTES) ?: return null
        val day = runCatching { json.decodeFromString<ArchiveDay>(raw) }.getOrNull() ?: return null
        if (day.date != date || day.quizzes.isEmpty() || day.quizzes.any { it.published != date }) return null
        val catalog = Json.encodeToString(LearningCatalog(quizzes = day.quizzes))
        return day.takeIf { LearningCatalogCodec.parse(catalog) != null }
    }

    fun page(bytes: ByteArray, before: String?): ArchivePage? {
        val raw = text(bytes, MAX_PAGE_BYTES) ?: return null
        val page = runCatching { json.decodeFromString<ArchivePage>(raw) }.getOrNull() ?: return null
        if (before != null && !validDate(before)) return null
        if (page.days.size > 30 || page.days.any { !validDate(it.date) || it.questions < 1 || (before != null && it.date >= before) }) return null
        if (page.days.zipWithNext().any { (a, b) -> a.date <= b.date }) return null
        if (page.nextBefore != null && (page.days.isEmpty() || page.nextBefore != page.days.last().date)) return null
        return page
    }
}

/** Public archive only. Locally cached days remain available offline and are never silently overwritten. */
class QuizArchiveClient(private val contentUrl: String, private val transport: ArchiveTransport, private val cache: ArchiveCache, private val lock: Mutex = Mutex()) {
    private fun base(): URI? = runCatching {
        URI(contentUrl).also { uri -> require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath == "/content" && uri.rawQuery == null && uri.rawFragment == null) }
    }.getOrNull()

    suspend fun cachedDates(): List<String>? = withContext(Dispatchers.IO) {
        try { cache.dates().filter(QuizArchiveCodec::validDate).distinct().sortedDescending() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun page(before: String? = null): ArchivePageResult {
        if (contentUrl.isBlank()) return ArchivePageResult.NotConfigured
        val base = base() ?: return ArchivePageResult.Unavailable
        if (before != null && !QuizArchiveCodec.validDate(before)) return ArchivePageResult.Unavailable
        return try {
            val url = base.resolve("/quiz-archive?limit=30" + (before?.let { "&before=$it" } ?: "")).toASCIIString()
            val bytes = transport.fetch(url, QuizArchiveCodec.MAX_PAGE_BYTES) ?: return ArchivePageResult.Unavailable
            QuizArchiveCodec.page(bytes, before)?.let { ArchivePageResult.Ready(it) } ?: ArchivePageResult.Unavailable
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ArchivePageResult.Unavailable }
    }

    suspend fun day(date: String): ArchiveDayResult = lock.withLock {
        if (!QuizArchiveCodec.validDate(date)) return@withLock ArchiveDayResult.Unavailable
        val local = try { withContext(Dispatchers.IO) { cache.read(date) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@withLock ArchiveDayResult.StorageError }
        if (local != null) return@withLock QuizArchiveCodec.day(local, date)?.let { ArchiveDayResult.Ready(it, true) }
            ?: ArchiveDayResult.StorageError
        if (contentUrl.isBlank()) return@withLock ArchiveDayResult.NotConfigured
        val base = base() ?: return@withLock ArchiveDayResult.Unavailable
        val bytes = try {
            transport.fetch(base.resolve("/quiz-archive/$date").toASCIIString(), QuizArchiveCodec.MAX_DAY_BYTES)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } ?: return@withLock ArchiveDayResult.Unavailable
        val day = QuizArchiveCodec.day(bytes, date) ?: return@withLock ArchiveDayResult.Unavailable
        try {
            withContext(Dispatchers.IO) {
                cache.writeNew(date, bytes)
                check(cache.read(date)?.contentEquals(bytes) == true)
            }
            ArchiveDayResult.Ready(day, false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ArchiveDayResult.StorageError }
    }
}

class HttpsArchiveTransport : ArchiveTransport {
    override suspend fun fetch(url: String, limit: Int): ByteArray? = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null &&
            ((uri.rawPath == "/quiz-archive" && Regex("^limit=30(?:&before=[0-9]{4}-[0-9]{2}-[0-9]{2})?$").matches(uri.rawQuery.orEmpty())) ||
             (Regex("^/quiz-archive/[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(uri.rawPath) && uri.rawQuery == null)))
        require(limit in 1..QuizArchiveCodec.MAX_DAY_BYTES)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode != 200) return@withContext null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) return@withContext null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { connection?.disconnect() }
    }
}
