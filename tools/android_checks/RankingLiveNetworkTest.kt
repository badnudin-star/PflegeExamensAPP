package de.pflegeexamen.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pflegeexamen.app.data.quiz.HttpsRankingTransport
import de.pflegeexamen.app.data.quiz.QuizRankingClient
import de.pflegeexamen.app.data.quiz.RankingLoad
import de.pflegeexamen.app.data.quiz.HttpsRegistrationTransport
import de.pflegeexamen.app.data.quiz.HttpsAccountDeletionTransport
import de.pflegeexamen.app.data.quiz.HttpsRankedQuizTransport
import de.pflegeexamen.app.data.quiz.HttpsQuizSubmissionTransport
import de.pflegeexamen.app.data.quiz.HttpsAttemptLookupTransport
import de.pflegeexamen.app.data.quiz.RankedQuizClient
import de.pflegeexamen.app.data.quiz.RankedQuizLoad
import de.pflegeexamen.app.data.quiz.QuizAttemptLookup
import de.pflegeexamen.app.data.quiz.AttemptLookup
import de.pflegeexamen.app.data.quiz.SecureRankingTokenGenerator
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    /** Creates one disposable production account; deletion leaves a pseudonymous barrier. */
    @Test fun syntheticAccountUsesActualAndroidHttpsTransports() = runBlocking {
        val origin = "https://pflege-examen-ranking.badnudin.workers.dev"
        val leaderboardUrl = "$origin/leaderboard"
        val quiz = (RankedQuizClient(leaderboardUrl, HttpsRankedQuizTransport()).load() as? RankedQuizLoad.Ready)?.quiz
        assertTrue("No valid current quiz; no test account created", quiz != null)
        val current = requireNotNull(quiz)
        val token = SecureRankingTokenGenerator().create()
        val name = "Probe-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Only identifiers for targeted investigation; never log the bearer token.
        Log.i("RankingLiveProbe", "Synthetic account: name=$name tokenSHA256=$hash")
        val registration = HttpsRegistrationTransport()
        val deletion = HttpsAccountDeletionTransport()
        var deleted = false
        try {
            val payload = JSONObject().put("username", name).toString().toByteArray(Charsets.UTF_8)
            val created = registration.send("POST", "$origin/participants", token, payload)
            assertEquals("Registration not confirmed", 201, created.status)
            assertEquals(name, JSONObject(created.body.toString(Charsets.UTF_8)).getString("username"))
            val status = registration.send("GET", "$origin/participants/me", token, null)
            assertEquals("Account lookup failed", 200, status.status)
            assertEquals(name, JSONObject(status.body.toString(Charsets.UTF_8)).getString("username"))
            val answers = JSONArray()
            current.questions.forEach { q -> answers.put(JSONObject().put("id", q.id).put("option", 0)) }
            val body = JSONObject().put("date", current.date).put("fingerprint", current.fingerprint)
                .put("answers", answers).toString().toByteArray(Charsets.UTF_8)
            val score = current.questions.count { it.correctIndex == 0 }
            val submission = HttpsQuizSubmissionTransport()
            val result = submission.send("$origin/quiz/attempt", token, body)
            assertEquals("Submission not confirmed", 201, result.status)
            val value = JSONObject(result.body.toString(Charsets.UTF_8))
            assertEquals(current.date, value.getString("date"))
            assertEquals(score, value.getInt("correct"))
            assertEquals(50, value.getInt("total"))
            assertEquals("Duplicate was not rejected", 409, submission.send("$origin/quiz/attempt", token, body).status)
            val found = QuizAttemptLookup(leaderboardUrl, HttpsAttemptLookupTransport()).lookup(current.date, token)
            assertTrue("Result lookup failed", found is AttemptLookup.Found)
            val lookup = found as AttemptLookup.Found
            val digest = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(score, lookup.correct)
            assertEquals(50, lookup.total)
            assertEquals(digest, lookup.payloadHash)
        } finally {
            repeat(3) { attempt ->
                if (!deleted) {
                    val result = runCatching { deletion.delete("$origin/participants/me", token) }.getOrNull()
                    deleted = result?.status == 204 && result.receipt == "v1"
                    if (!deleted && attempt < 2) delay(3000)
                }
            }
            if (!deleted) Log.e("RankingLiveProbe", "Deletion unconfirmed: name=$name tokenSHA256=$hash")
            assertTrue("Synthetic account deletion not confirmed; investigate identifiers in logcat", deleted)
        }
        val after = registration.send("GET", "$origin/participants/me", token, null)
        assertEquals("Deleted account still readable or status uncertain", 404, after.status)
        Log.i("RankingLiveProbe", "Synthetic account deletion confirmed: name=$name tokenSHA256=$hash")
        Unit // JUnit requires a void test method, not Log.i's Int return value.
    }
}
