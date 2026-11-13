package de.pflegeexamen.app

import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.domain.model.LeaderboardEntry
import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.RankingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingEngineTest {

    @Test
    fun `validateUsername enforces length and allowed characters`() {
        assertNotNull(RankingEngine.validateUsername("A"))
        assertNotNull(RankingEngine.validateUsername("   "))
        assertNotNull(RankingEngine.validateUsername("NameMit!Unerlaubten"))
        assertNull(RankingEngine.validateUsername("PflegeFuchs_27"))
        assertNull(RankingEngine.validateUsername("Klara-Station3"))
    }

    @Test
    fun `isHourlySyncDue respects one hour interval and missing snapshot`() {
        val now = 10_000_000L
        assertTrue(RankingEngine.isHourlySyncDue(lastSyncMillis = 0L, snapshot = null, nowMillis = now))
        val snap = LeaderboardSnapshot(
            generatedAtMillis = now - 60_000L,
            nextUpdateAtMillis = now + 3_540_000L,
            totalParticipants = 1,
            entries = emptyList()
        )
        assertFalse(
            RankingEngine.isHourlySyncDue(
                lastSyncMillis = now - 30L * 60L * 1000L,
                snapshot = snap,
                nowMillis = now
            )
        )
        assertTrue(
            RankingEngine.isHourlySyncDue(
                lastSyncMillis = now - 61L * 60L * 1000L,
                snapshot = snap,
                nowMillis = now
            )
        )
    }

    @Test
    fun `buildDisplaySnapshot inserts local user and sorts ranks`() {
        val settings = RankingSettings(
            userId = "u-self",
            username = "PflegeProfi",
            rankingOptIn = true,
            endpointUrl = "https://example.workers.dev"
        )
        val cached = LeaderboardSnapshot(
            generatedAtMillis = 1_000L,
            nextUpdateAtMillis = 3_601_000L,
            totalParticipants = 2,
            entries = listOf(
                LeaderboardEntry(1, "u-1", "Anna", 500, 5, "Stationserfahren"),
                LeaderboardEntry(2, "u-2", "Lukas", 100, 1, "1. Ausbildungsjahr")
            )
        )

        val result = RankingEngine.buildDisplaySnapshot(
            cachedSnapshot = cached,
            settings = settings,
            localXp = 250,
            localStreakDays = 3,
            localRankTitle = "2. Ausbildungsjahr",
            nowMillis = 2_000L
        )

        assertEquals(3, result.entries.size)
        assertEquals("Anna", result.entries[0].username)
        assertEquals(1, result.entries[0].rank)
        assertEquals("PflegeProfi", result.entries[1].username)
        assertTrue(result.entries[1].isCurrentUser)
        assertEquals(2, result.entries[1].rank)
        assertEquals("Lukas", result.entries[2].username)
        assertEquals(3, result.entries[2].rank)
    }
}
