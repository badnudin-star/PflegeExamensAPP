package de.pflegeexamen.app.domain.quiz

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RankingSnapshotTest {
    private val snapshot = QuizRankingSnapshot(1_700_000_000_000, 1_700_000_001_000, 2,
        listOf(QuizRankingEntry(1, "PflegeFuchs", 75, 2), QuizRankingEntry(2, "Biene", 50, 1)))
    private fun parse(value: QuizRankingSnapshot) = RankingSnapshotCodec.parse(Json.encodeToString(value).toByteArray())

    @Test fun `accepts valid sorted snapshot and empty leaderboard`() {
        assertEquals(snapshot, parse(snapshot))
        assertNotNull(parse(snapshot.copy(entries = emptyList(), totalParticipants = 0)))
        assertNotNull(parse(snapshot.copy(entries = List(100) { QuizRankingEntry(it + 1, "Person", 0, 1) }, totalParticipants = 100)))
    }

    @Test fun `rejects invalid ranks scores counts timestamps and names`() {
        val first = snapshot.entries.first()
        val invalid = listOf(
            snapshot.copy(entries = listOf(first.copy(rank = 2))),
            snapshot.copy(entries = listOf(first.copy(points = 101))),
            snapshot.copy(entries = listOf(first.copy(points = -1))),
            snapshot.copy(entries = listOf(first.copy(quizzes = 0))),
            snapshot.copy(entries = listOf(first.copy(quizzes = Long.MAX_VALUE))),
            snapshot.copy(entries = listOf(first.copy(username = ""))),
            snapshot.copy(entries = listOf(first.copy(username = "X\nY"))),
            snapshot.copy(entries = listOf(first.copy(username = "x".repeat(25)))),
            snapshot.copy(totalParticipants = 1),
            snapshot.copy(entries = snapshot.entries.reversed().mapIndexed { i, e -> e.copy(rank = i + 1) }),
            snapshot.copy(generatedAtMillis = 0),
            snapshot.copy(nextUpdateAtMillis = snapshot.generatedAtMillis),
            snapshot.copy(nextUpdateAtMillis = snapshot.generatedAtMillis + 3_600_001),
            snapshot.copy(entries = List(101) { QuizRankingEntry(it + 1, "Person", 0, 1) }, totalParticipants = 101)
        )
        invalid.forEach { assertNull(parse(it)) }
    }

    @Test fun `rejects malformed utf8 and oversized data`() {
        assertNull(RankingSnapshotCodec.parse("{bad}".toByteArray()))
        assertNull(RankingSnapshotCodec.parse(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(RankingSnapshotCodec.parse(ByteArray(RankingSnapshotCodec.MAX_BYTES + 1)))
    }
}
