package de.pflegeexamen.app.data.ranking

import de.pflegeexamen.app.domain.model.RankingSettings
import de.pflegeexamen.app.domain.model.ScoreSubmissionPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HttpRankingClientTest {
    @Test
    fun `legacy client refuses connection tests and sync without contacting the endpoint`() = runBlocking {
        val requests = AtomicInteger()
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            requests.incrementAndGet()
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()
                            )
                        }
                    }
                } catch (_: SocketException) {
                    // Expected when the test closes the server.
                }
            }
            try {
                val client = HttpRankingClient()
                val endpoint = "http://127.0.0.1:${server.localPort}"
                val payload = ScoreSubmissionPayload("id", "Name", 100, 3, "Rang")
                for (optIn in listOf(true, false)) {
                    assertEquals(
                        RankingSyncResult.Failure(LEGACY_RANKING_DISABLED_MESSAGE),
                        client.syncWithEndpoint(
                            RankingSettings(userId = "id", username = "Name", endpointUrl = endpoint, rankingOptIn = optIn),
                            payload
                        )
                    )
                }
                assertEquals(false to LEGACY_RANKING_DISABLED_MESSAGE, client.testConnection(endpoint))
                assertEquals(false to LEGACY_RANKING_DISABLED_MESSAGE, client.testConnection(""))
                assertEquals(
                    RankingSyncResult.Failure(LEGACY_RANKING_DISABLED_MESSAGE),
                    client.syncWithEndpoint(RankingSettings(endpointUrl = "https://example.invalid"), payload)
                )
            } finally {
                server.close()
                responder.join(2000)
            }
            assertEquals(0, requests.get())
        }
    }
}
