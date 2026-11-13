package de.pflegeexamen.app.data.ai

import android.content.Context
import de.pflegeexamen.app.domain.model.AiProviderMode
import de.pflegeexamen.app.domain.model.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lokaler Speicher für kostenlose KI-Einstellungen und einmalige Start-Initialisierung.
 * API-Keys bleiben ausschließlich auf dem Gerät des Nutzers.
 */
class AiPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences("pflege_examen_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    fun current(): AiSettings = _settings.value

    fun updateSettings(newSettings: AiSettings) {
        prefs.edit()
            .putString(KEY_MODE, newSettings.mode.name)
            .putString(KEY_GROQ_KEY, newSettings.groqApiKey.trim())
            .putString(KEY_GROQ_MODEL, newSettings.groqModel.trim())
            .putString(KEY_GEMINI_KEY, newSettings.geminiApiKey.trim())
            .putString(KEY_GEMINI_MODEL, newSettings.geminiModel.trim())
            .putString(KEY_OLLAMA_URL, newSettings.ollamaBaseUrl.trim())
            .putString(KEY_OLLAMA_MODEL, newSettings.ollamaModel.trim())
            .apply()
        _settings.value = loadSettings()
    }

    fun isInitialCatalogSeeded(): Boolean = prefs.getBoolean(KEY_CATALOGS_SEEDED, false)

    fun markInitialCatalogSeeded() {
        prefs.edit().putBoolean(KEY_CATALOGS_SEEDED, true).apply()
    }

    fun getLastSeededVersionCode(): Int = prefs.getInt(KEY_SEED_VERSION_CODE, 0)

    fun setLastSeededVersionCode(version: Int) {
        prefs.edit().putInt(KEY_SEED_VERSION_CODE, version).apply()
    }

    fun getLastSeedBundleTag(): String = prefs.getString(KEY_SEED_BUNDLE_TAG, "").orEmpty()

    fun setLastSeedBundleTag(tag: String) {
        prefs.edit().putString(KEY_SEED_BUNDLE_TAG, tag).apply()
    }

    private fun loadSettings(): AiSettings {
        // Standard ist die Smartphone-interne KI. Nur wenn der Nutzer bewusst einen anderen
        // Modus gespeichert hat, wird dieser übernommen (Bestandskunden migrieren automatisch).
        val mode = if (prefs.contains(KEY_MODE)) {
            runCatching {
                AiProviderMode.valueOf(prefs.getString(KEY_MODE, AiProviderMode.ON_DEVICE.name)!!)
            }.getOrDefault(AiProviderMode.ON_DEVICE)
        } else {
            AiProviderMode.ON_DEVICE
        }

        return AiSettings(
            mode = mode,
            groqApiKey = prefs.getString(KEY_GROQ_KEY, "").orEmpty(),
            groqModel = prefs.getString(KEY_GROQ_MODEL, AiProviderMode.GROQ_FREE.defaultModel)
                .orEmpty().ifBlank { AiProviderMode.GROQ_FREE.defaultModel },
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "").orEmpty(),
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, AiProviderMode.GEMINI_FREE.defaultModel)
                .orEmpty().ifBlank { AiProviderMode.GEMINI_FREE.defaultModel },
            ollamaBaseUrl = prefs.getString(KEY_OLLAMA_URL, "http://192.168.178.20:11434")
                .orEmpty().ifBlank { "http://192.168.178.20:11434" },
            ollamaModel = prefs.getString(KEY_OLLAMA_MODEL, AiProviderMode.OLLAMA_LOCAL.defaultModel)
                .orEmpty().ifBlank { AiProviderMode.OLLAMA_LOCAL.defaultModel }
        )
    }

    companion object {
        private const val KEY_MODE = "ai_mode"
        private const val KEY_GROQ_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OLLAMA_URL = "ollama_base_url"
        private const val KEY_OLLAMA_MODEL = "ollama_model"
        private const val KEY_CATALOGS_SEEDED = "initial_catalogs_seeded_v2"
        private const val KEY_SEED_VERSION_CODE = "seed_version_code"
        private const val KEY_SEED_BUNDLE_TAG = "seed_bundle_tag"
    }
}
