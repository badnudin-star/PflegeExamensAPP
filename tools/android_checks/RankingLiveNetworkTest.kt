package de.pflegeexamen.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pflegeexamen.app.data.quiz.HttpsRankingTransport
import de.pflegeexamen.app.data.quiz.QuizRankingClient
import de.pflegeexamen.app.data.quiz.RankingLoad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in manual emulator test. The public read may initialize an empty snapshot. */
@RunWith(AndroidJUnit4::class)
class RankingLiveNetworkTest {
    @Test fun publicRankingLoadsUsingTheActualAndroidTransport() = runBlocking {
        val client = QuizRankingClient("https://pflege-examen-ranking.badnudin.workers.dev/leaderboard", HttpsRankingTransport())
        assertTrue("Ranking endpoint was unavailable or returned an invalid snapshot", client.load() is RankingLoad.Ready)
    }
}
