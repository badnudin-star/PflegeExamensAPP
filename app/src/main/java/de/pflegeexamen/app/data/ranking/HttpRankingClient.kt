package de.pflegeexamen.app.data.ranking

import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.RankingSettings
import de.pflegeexamen.app.domain.model.ScoreSubmissionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface RankingSyncResult {
    data class Success(
        val snapshot: LeaderboardSnapshot,
        val statusMessage: String
    ) : RankingSyncResult

    data object NotConfigured : RankingSyncResult

    data class Failure(val reason: String) : RankingSyncResult
}

/**
 * Kostenloser HTTP-Client (ohne Drittanbieter-SDK) für den Cloudflare-Worker
 * bzw. eine statische GitHub-Pages `leaderboard.json`.
 */
class HttpRankingClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun syncWithEndpoint(
        settings: RankingSettings,
        payload: ScoreSubmissionPayload
    ): RankingSyncResult = withContext(Dispatchers.IO) {
        if (!settings.isEndpointConfigured()) {
            return@withContext RankingSyncResult.NotConfigured
        }

        val base = settings.normalizedEndpoint()
        val isStaticJsonUrl = base.lowercase().endsWith(".json")

        runCatching {
            if (!isStaticJsonUrl && settings.rankingOptIn && settings.hasUsername()) {
                val body = json.encodeToString(payload)
                val response = executeRequest(
                    urlStr = "$base/score",
                    method = "POST",
                    jsonBody = body
                )
                val snapshot = json.decodeFromString<LeaderboardSnapshot>(response)
                RankingSyncResult.Success(
                    snapshot = snapshot,
                    statusMessage = "Stand übermittelt & stündliches Ranking geladen."
                )
            } else {
                val readUrl = if (isStaticJsonUrl) base else "$base/leaderboard"
                val response = executeRequest(
                    urlStr = readUrl,
                    method = "GET",
                    jsonBody = null
                )
                val snapshot = json.decodeFromString<LeaderboardSnapshot>(response)
                RankingSyncResult.Success(
                    snapshot = snapshot,
                    statusMessage = "Stündliches Ranking aktualisiert."
                )
            }
        }.getOrElse { err ->
            RankingSyncResult.Failure(
                err.message ?: "Verbindung zum Ranking-Endpunkt fehlgeschlagen."
            )
        }
    }

    suspend fun testConnection(endpointUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = endpointUrl.trim().trimEnd('/')
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) {
            return@withContext false to "Bitte eine gültige URL (https://...) eingeben."
        }
        val testUrl = if (clean.lowercase().endsWith(".json")) clean else "$clean/health"
        runCatching {
            val res = executeRequest(testUrl, method = "GET", jsonBody = null)
            val preview = res.take(120).replace("\n", " ")
            true to "Erreichbar: $preview"
        }.getOrElse { err ->
            false to "Nicht erreichbar: ${err.message ?: "Unbekannter Fehler"}"
        }
    }

    private fun executeRequest(urlStr: String, method: String, jsonBody: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                    writer.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${responseText.take(140)}")
            }
            return responseText
        } finally {
            conn.disconnect()
        }
    }
}
