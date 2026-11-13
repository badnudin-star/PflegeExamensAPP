package de.pflegeexamen.app.data.ai

import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import de.pflegeexamen.app.domain.ai.PromptPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Status der Smartphone-internen KI (Android AICore / Gemini Nano) auf diesem Gerät.
 */
enum class OnDeviceAiStatus {
    /** Modell ist heruntergeladen und sofort einsatzbereit. */
    AVAILABLE,

    /** Modell kann heruntergeladen werden (noch nicht auf dem Gerät). */
    DOWNLOADABLE,

    /** Download läuft bereits. */
    DOWNLOADING,

    /** Auf diesem Gerät nicht verfügbar (kein AICore / kein Gemini Nano). */
    UNAVAILABLE,

    /** Android-Version zu alt (AICore braucht Android 12 / API 31). */
    UNSUPPORTED
}

/**
 * Smartphone-interne KI über die ML Kit GenAI Prompt API (Android AICore / Gemini Nano).
 *
 * Läuft zu 100 % auf dem Gerät: kein Internet, kein API-Key, keine Kosten.
 * Die ML-Kit-Klassen werden nur angefasst, wenn [Build.VERSION_CODES.S] erfüllt ist –
 * darunter (minSdk 26) meldet der Client sauber [OnDeviceAiStatus.UNSUPPORTED] und die
 * App fällt automatisch auf die lokale Offline-Engine zurück.
 *
 * Wichtig für Gemini Nano: Prompts bleiben kompakt (ein einziger TextPart, max. ~3.500
 * Zeichen), damit das kleine On-Device-Kontextfenster (~4.096 Tokens) nie überläuft.
 */
class OnDeviceAiClient {

    @Volatile
    private var model: GenerativeModel? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isSupportedPlatform(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun client(): GenerativeModel =
        model ?: Generation.getClient(generationConfig {}).also { model = it }

    /** Fragt AICore nach dem aktuellen Modell-Status. Wirft nie. */
    suspend fun checkStatus(): OnDeviceAiStatus = withContext(Dispatchers.IO) {
        if (!isSupportedPlatform()) return@withContext OnDeviceAiStatus.UNSUPPORTED
        try {
            when (withTimeoutOrNull(15_000L) { client().checkStatus() }) {
                FeatureStatus.AVAILABLE -> OnDeviceAiStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> OnDeviceAiStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> OnDeviceAiStatus.DOWNLOADING
                FeatureStatus.UNAVAILABLE -> OnDeviceAiStatus.UNAVAILABLE
                else -> OnDeviceAiStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OnDeviceAiStatus.UNAVAILABLE
        }
    }

    /**
     * Lädt das Modell bei Bedarf herunter und wartet bis es einsatzbereit ist.
     * Gibt `true` zurück, wenn danach generiert werden kann.
     */
    suspend fun downloadAndWait(onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSupportedPlatform()) return@withContext false
            when (checkStatus()) {
                OnDeviceAiStatus.AVAILABLE -> return@withContext true
                OnDeviceAiStatus.UNAVAILABLE, OnDeviceAiStatus.UNSUPPORTED -> return@withContext false
                OnDeviceAiStatus.DOWNLOADABLE, OnDeviceAiStatus.DOWNLOADING -> Unit
            }
            var total = 0L
            var completed = false
            try {
                client().download()
                    .transformWhile { status ->
                        emit(status)
                        status !is DownloadStatus.DownloadCompleted &&
                            status !is DownloadStatus.DownloadFailed
                    }
                    .collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadStarted -> {
                                total = status.bytesToDownload
                                onProgress(0L, total)
                            }
                            is DownloadStatus.DownloadProgress -> {
                                onProgress(status.totalBytesDownloaded, total)
                            }
                            is DownloadStatus.DownloadCompleted -> {
                                completed = true
                                onProgress(total.coerceAtLeast(1L), total.coerceAtLeast(1L))
                            }
                            is DownloadStatus.DownloadFailed -> Unit
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext false
            }
            if (!completed) return@withContext false
            checkStatus() == OnDeviceAiStatus.AVAILABLE
        }

    /** Startet den Modell-Download im Hintergrund (nicht blockierend). */
    fun startBackgroundDownload() {
        if (!isSupportedPlatform()) return
        scope.launch {
            runCatching {
                client().download().collect { /* Fortschritt wird nicht benötigt */ }
            }
        }
    }

    /**
     * Erzeugt Text direkt auf dem Smartphone. Bei jedem Problem (Modell fehlt, lädt noch,
     * nicht unterstützt, Fehler) wird ein [AiCallResult.Failure] zurückgegeben, damit die
     * Aufrufer nahtlos auf die lokale Offline-Engine zurückfallen können.
     */
    suspend fun complete(
        prompt: PromptPair,
        maxOutputTokens: Int = 1280,
        timeoutMillis: Long = 120_000L
    ): AiCallResult = withContext(Dispatchers.IO) {
        if (!isSupportedPlatform()) {
            return@withContext AiCallResult.Failure(
                "Smartphone-KI benötigt Android 12 oder neuer."
            )
        }
        when (checkStatus()) {
            OnDeviceAiStatus.AVAILABLE -> Unit
            OnDeviceAiStatus.DOWNLOADABLE -> {
                startBackgroundDownload()
                return@withContext AiCallResult.Failure(
                    "Smartphone-KI wird gerade heruntergeladen – bitte in wenigen Minuten erneut versuchen. Offline-Engine übernimmt."
                )
            }
            OnDeviceAiStatus.DOWNLOADING -> return@withContext AiCallResult.Failure(
                "Smartphone-KI lädt noch herunter – bitte gleich erneut versuchen. Offline-Engine übernimmt."
            )
            OnDeviceAiStatus.UNAVAILABLE, OnDeviceAiStatus.UNSUPPORTED -> return@withContext AiCallResult.Failure(
                "Smartphone-KI auf diesem Gerät nicht verfügbar."
            )
        }

        // System- und Nutzer-Prompt in EINEN TextPart packen (bewährt für Gemini Nano).
        val fullPrompt = (prompt.systemPrompt + "\n\n" + prompt.userPrompt)
            .take(MAX_PROMPT_CHARS)

        try {
            val request = generateContentRequest(TextPart(fullPrompt)) {
                temperature = 0.2f
                topK = 16
                candidateCount = 1
                this.maxOutputTokens = maxOutputTokens
            }
            val response = withTimeoutOrNull(timeoutMillis) {
                client().generateContent(request)
            }
            var text = response?.candidates?.joinToString("") { it.text }.orEmpty()

            if (text.isBlank()) {
                // Fallback über den Streaming-Pfad (anderer Dienstweg im System).
                val streamed = withTimeoutOrNull(timeoutMillis) {
                    client().generateContentStream(request).toList()
                        .joinToString("") { it.candidates.joinToString("") { c -> c.text } }
                }.orEmpty()
                text = streamed
            }

            if (text.isBlank()) {
                AiCallResult.Failure("Leere Antwort der Smartphone-KI erhalten.")
            } else {
                AiCallResult.Success(text, "Smartphone-KI (Gemini Nano)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Client neu aufbauen (z. B. nach Verbindungsabbruch des Systemdienstes).
            runCatching { model?.close() }
            model = null
            AiCallResult.Failure(
                "Smartphone-KI-Fehler: ${e.message ?: "unbekannt"}."
            )
        }
    }

    /** Kurzer Verbindungstest für die Einstellungen. */
    suspend fun testConnection(): AiCallResult {
        if (!isSupportedPlatform()) {
            return AiCallResult.Failure("Smartphone-KI benötigt Android 12 oder neuer.")
        }
        return when (checkStatus()) {
            OnDeviceAiStatus.AVAILABLE -> complete(
                prompt = PromptPair(
                    systemPrompt = "Antworte kurz auf Deutsch.",
                    userPrompt = "Antworte nur mit: Verbindung erfolgreich."
                ),
                maxOutputTokens = 64,
                timeoutMillis = 60_000L
            )
            OnDeviceAiStatus.DOWNLOADABLE ->
                AiCallResult.Failure("Smartphone-KI noch nicht heruntergeladen – bitte zuerst aktivieren.")
            OnDeviceAiStatus.DOWNLOADING ->
                AiCallResult.Failure("Smartphone-KI lädt noch herunter – bitte kurz warten.")
            OnDeviceAiStatus.UNAVAILABLE ->
                AiCallResult.Failure("Smartphone-KI auf diesem Gerät nicht verfügbar (kein Gemini Nano).")
            OnDeviceAiStatus.UNSUPPORTED ->
                AiCallResult.Failure("Smartphone-KI benötigt Android 12 oder neuer.")
        }
    }

    companion object {
        /** Obergrenze für Gemini Nano: kompakte Prompts verhindern REQUEST_TOO_LARGE. */
        const val MAX_PROMPT_CHARS = 3_500
    }
}
