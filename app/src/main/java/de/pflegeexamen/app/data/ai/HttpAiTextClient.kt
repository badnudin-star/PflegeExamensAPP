package de.pflegeexamen.app.data.ai

import de.pflegeexamen.app.domain.ai.PromptPair
import de.pflegeexamen.app.domain.model.AiProviderMode
import de.pflegeexamen.app.domain.model.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface AiCallResult {
    data class Success(val text: String, val providerLabel: String) : AiCallResult
    data class Failure(val reason: String) : AiCallResult
}

/**
 * Schlanker, abhängigkeitsfreier HTTP-Client für kostenlose KI-Endpunkte:
 * - Groq Free Tier (OpenAI-kompatibel, 0 €, ohne Kreditkarte)
 * - Google Gemini Free Tier (Google AI Studio, 0 €, ohne Kreditkarte)
 * - Lokales Ollama im Heim-WLAN (0 €, 100 % privat)
 */
class HttpAiTextClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        settings: AiSettings,
        prompt: PromptPair,
        expectJson: Boolean = true
    ): AiCallResult = withContext(Dispatchers.IO) {
        if (!settings.isReadyForRemoteCall()) {
            return@withContext AiCallResult.Failure("KI-Modus nicht konfiguriert oder API-Key fehlt.")
        }

        try {
            when (settings.mode) {
                AiProviderMode.ON_DEVICE ->
                    AiCallResult.Failure("Smartphone-KI läuft direkt auf dem Gerät – kein HTTP-Aufruf nötig.")
                AiProviderMode.OFFLINE_ONLY ->
                    AiCallResult.Failure("Offline-Modus aktiv.")
                AiProviderMode.GROQ_FREE ->
                    callGroq(settings, prompt, expectJson)
                AiProviderMode.GEMINI_FREE ->
                    callGemini(settings, prompt, expectJson)
                AiProviderMode.OLLAMA_LOCAL ->
                    callOllama(settings, prompt, expectJson)
            }
        } catch (e: Exception) {
            AiCallResult.Failure(e.message ?: "Verbindung zum KI-Dienst fehlgeschlagen.")
        }
    }

    suspend fun testConnection(settings: AiSettings): AiCallResult {
        if (settings.mode == AiProviderMode.OFFLINE_ONLY) {
            return AiCallResult.Success("100 % Offline-Modus aktiv – keine Verbindung nötig.", "Offline")
        }
        return complete(
            settings = settings,
            prompt = PromptPair(
                systemPrompt = "Antworte kurz auf Deutsch.",
                userPrompt = "Antworte nur mit: Verbindung erfolgreich."
            ),
            expectJson = false
        )
    }

    private fun callGroq(settings: AiSettings, prompt: PromptPair, expectJson: Boolean): AiCallResult {
        val model = settings.groqModel.ifBlank { AiProviderMode.GROQ_FREE.defaultModel }
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 2800)
            if (expectJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", prompt.systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt.userPrompt)
                })
            })
        }.toString()

        val responseBody = postJson(
            urlStr = "https://api.groq.com/openai/v1/chat/completions",
            body = payload,
            headers = mapOf("Authorization" to "Bearer ${settings.groqApiKey}")
        )

        val root = json.parseToJsonElement(responseBody).jsonObject
        val content = root["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            .orEmpty()

        return if (content.isBlank()) {
            AiCallResult.Failure("Leere Antwort von Groq erhalten.")
        } else {
            AiCallResult.Success(content, "Groq ($model)")
        }
    }

    private fun callGemini(settings: AiSettings, prompt: PromptPair, expectJson: Boolean): AiCallResult {
        val model = settings.geminiModel.ifBlank { AiProviderMode.GEMINI_FREE.defaultModel }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${settings.geminiApiKey}"

        val payload = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", prompt.systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt.userPrompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
                if (expectJson) {
                    put("responseMimeType", "application/json")
                }
            })
        }.toString()

        val responseBody = postJson(urlStr = url, body = payload)
        val root = json.parseToJsonElement(responseBody).jsonObject
        val parts = root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
        val content = parts?.joinToString("") { el ->
            el.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }.orEmpty()

        return if (content.isBlank()) {
            AiCallResult.Failure("Leere Antwort von Gemini erhalten.")
        } else {
            AiCallResult.Success(content, "Gemini ($model)")
        }
    }

    private fun callOllama(settings: AiSettings, prompt: PromptPair, expectJson: Boolean): AiCallResult {
        val base = settings.ollamaBaseUrl.trim().trimEnd('/')
        val model = settings.ollamaModel.ifBlank { AiProviderMode.OLLAMA_LOCAL.defaultModel }
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            if (expectJson) put("format", "json")
            put("options", buildJsonObject { put("temperature", 0.2) })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", prompt.systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt.userPrompt)
                })
            })
        }.toString()

        val responseBody = postJson(urlStr = "$base/api/chat", body = payload, timeoutMs = 45_000)
        val root = json.parseToJsonElement(responseBody).jsonObject
        val content = root["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()

        return if (content.isBlank()) {
            AiCallResult.Failure("Leere Antwort von Ollama erhalten.")
        } else {
            AiCallResult.Success(content, "Ollama ($model)")
        }
    }

    private fun postJson(
        urlStr: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 25_000
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(180)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
