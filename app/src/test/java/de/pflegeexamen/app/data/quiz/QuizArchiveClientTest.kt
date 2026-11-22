package de.pflegeexamen.app.data.quiz

import de.pflegeexamen.app.domain.content.PublishedQuiz
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QuizArchiveClientTest {
    private val date = "2020-02-29"
    private val quiz = PublishedQuiz("q1", date, DefaultCatalogs.defaultPreparations.first().title, "Frage?", listOf("A", "B", "C", "D"), 1, "Erklärung", listOf("Test"))
    private val bytes = Json.encodeToString(ArchiveDay(date, listOf(quiz))).toByteArray()
    private class Cache : ArchiveCache {
        val values = mutableMapOf<String, ByteArray>()
        var writes = 0
        var fail = false
        override fun dates() = if (fail) error("disk") else values.keys.toList()
        override fun read(date: String): ByteArray? { if (fail) error("disk"); return values[date] }
        override fun writeNew(date: String, bytes: ByteArray) { if (fail) error("disk"); check(date !in values); values[date] = bytes; writes++ }
    }

    @Test fun `offline cache is used before network and never overwritten`() = runTest {
        val cache = Cache()
        var requests = 0
        val online = QuizArchiveClient("https://example.org/content", ArchiveTransport { url, _ ->
            requests++; assertEquals("https://example.org/quiz-archive/$date", url); bytes
        }, cache)
        assertEquals(ArchiveDayResult.Ready(ArchiveDay(date, listOf(quiz)), false), online.day(date))
        assertEquals(1, cache.writes)
        assertEquals(listOf(date), online.cachedDates())
        val offline = QuizArchiveClient("", ArchiveTransport { _, _ -> error("no network") }, cache)
        assertEquals(ArchiveDayResult.Ready(ArchiveDay(date, listOf(quiz)), true), offline.day(date))
        assertEquals(1, requests)
        cache.values[date] = "corrupt".toByteArray()
        assertEquals(ArchiveDayResult.StorageError, online.day(date))
        assertEquals(1, requests)
        assertEquals(1, cache.writes)
        assertEquals("corrupt", cache.values[date]!!.toString(Charsets.UTF_8))
    }

    @Test fun `day rejects unsafe responses and distinguishes network and storage failure`() = runTest {
        val cache = Cache()
        var called = 0
        val transport = ArchiveTransport { _, _ -> called++; bytes }
        assertEquals(ArchiveDayResult.NotConfigured, QuizArchiveClient("", transport, cache).day(date))
        for (url in listOf("http://example.org/content", "https://u:p@example.org/content", "https://example.org/content?x=1", "https://example.org/other"))
            assertEquals(ArchiveDayResult.Unavailable, QuizArchiveClient(url, transport, cache).day(date))
        assertEquals(ArchiveDayResult.Unavailable, QuizArchiveClient("https://example.org/content", transport, cache).day("2020-02-30"))
        assertEquals(0, called)
        assertEquals(ArchiveDayResult.Unavailable, QuizArchiveClient("https://example.org/content", ArchiveTransport { _, _ -> error("network") }, cache).day(date))
        assertEquals(ArchiveDayResult.Unavailable, QuizArchiveClient("https://example.org/content", ArchiveTransport { _, _ -> ByteArray(QuizArchiveCodec.MAX_DAY_BYTES + 1) }, cache).day(date))
        assertEquals(ArchiveDayResult.Unavailable, QuizArchiveClient("https://example.org/content", ArchiveTransport { _, _ -> bytes }, cache).day("2020-03-01"))
        cache.fail = true
        assertEquals(ArchiveDayResult.StorageError, QuizArchiveClient("https://example.org/content", transport, cache).day(date))
        assertNull(QuizArchiveClient("", transport, cache).cachedDates())
        try {
            QuizArchiveClient("https://example.org/content", ArchiveTransport { _, _ -> throw CancellationException() }, Cache()).day(date)
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `decode archive day validates complete quiz and requested date`() {
        assertNotNull(QuizArchiveCodec.day(bytes, date))
        assertNull(QuizArchiveCodec.day(bytes, "2020-03-01"))
        assertNull(QuizArchiveCodec.day(Json.encodeToString(ArchiveDay(date, emptyList())).toByteArray(), date))
        assertNull(QuizArchiveCodec.day(Json.encodeToString(ArchiveDay(date, listOf(quiz, quiz))).toByteArray(), date))
        assertNull(QuizArchiveCodec.day(Json.encodeToString(ArchiveDay(date, listOf(quiz.copy(correctIndex=4)))).toByteArray(), date))
        assertNull(QuizArchiveCodec.day(Json.encodeToString(ArchiveDay(date, listOf(quiz.copy(published="2020-03-01")))).toByteArray(), date))
        assertNull(QuizArchiveCodec.day(byteArrayOf(0xC3.toByte(), 0x28), date))
    }

    @Test fun `page validates ordering boundaries and follows cursor`() = runTest {
        val page = ArchivePage(listOf(ArchiveDaySummary("2020-01-02", 50), ArchiveDaySummary("2020-01-01", 50)), "2020-01-01")
        val json = Json.encodeToString(page).toByteArray()
        val cache = Cache()
        var called = ""
        val client = QuizArchiveClient("https://example.org/content", ArchiveTransport { url, _ -> called=url; json }, cache)
        assertEquals(ArchivePageResult.Ready(page), client.page("2020-01-03"))
        assertEquals("https://example.org/quiz-archive?limit=30&before=2020-01-03", called)
        assertNull(QuizArchiveCodec.page(json, "2020-01-02"))
        assertNull(QuizArchiveCodec.page(Json.encodeToString(page.copy(days=page.days.reversed())).toByteArray(), null))
        assertNull(QuizArchiveCodec.page(Json.encodeToString(page.copy(nextBefore="2020-01-02")).toByteArray(), null))
        assertNull(QuizArchiveCodec.page(Json.encodeToString(page.copy(days=listOf(page.days[0],page.days[0]))).toByteArray(), null))
        assertNull(QuizArchiveCodec.page(Json.encodeToString(page.copy(days=listOf(ArchiveDaySummary("2020-02-30", 1)))).toByteArray(), null))
        assertNull(QuizArchiveCodec.page(ByteArray(QuizArchiveCodec.MAX_PAGE_BYTES + 1), null))
    }
}
