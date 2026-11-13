package de.pflegeexamen.app.data.ranking

import android.content.Context
import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.RankingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class RankingPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences("pflege_examen_ranking_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<RankingSettings> = _settings.asStateFlow()

    private val _cachedSnapshot = MutableStateFlow(loadCachedSnapshot())
    val cachedSnapshot: StateFlow<LeaderboardSnapshot?> = _cachedSnapshot.asStateFlow()

    fun currentSettings(): RankingSettings = _settings.value

    fun currentSnapshot(): LeaderboardSnapshot? = _cachedSnapshot.value

    fun updateUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username.trim()).apply()
        _settings.value = loadSettings()
    }

    fun updateSettings(
        username: String = _settings.value.username,
        rankingOptIn: Boolean = _settings.value.rankingOptIn,
        endpointUrl: String = _settings.value.endpointUrl
    ) {
        prefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putBoolean(KEY_OPT_IN, rankingOptIn)
            .putString(KEY_ENDPOINT_URL, endpointUrl.trim().trimEnd('/'))
            .apply()
        _settings.value = loadSettings()
    }

    fun saveSyncResult(snapshot: LeaderboardSnapshot, syncTimeMillis: Long = System.currentTimeMillis()) {
        val raw = runCatching { json.encodeToString(snapshot) }.getOrNull()
        val editor = prefs.edit().putLong(KEY_LAST_SYNC, syncTimeMillis)
        if (raw != null) {
            editor.putString(KEY_CACHED_SNAPSHOT, raw)
        }
        editor.apply()
        _settings.value = loadSettings()
        _cachedSnapshot.value = snapshot
    }

    private fun ensureUserId(): String {
        val existing = prefs.getString(KEY_USER_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }

    private fun loadSettings(): RankingSettings {
        val uid = ensureUserId()
        return RankingSettings(
            userId = uid,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            rankingOptIn = prefs.getBoolean(KEY_OPT_IN, true),
            endpointUrl = prefs.getString(KEY_ENDPOINT_URL, RankingSettings.DEFAULT_RANKING_ENDPOINT_URL)
                .orEmpty()
                .ifBlank { RankingSettings.DEFAULT_RANKING_ENDPOINT_URL },
            lastSyncMillis = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    private fun loadCachedSnapshot(): LeaderboardSnapshot? {
        val raw = prefs.getString(KEY_CACHED_SNAPSHOT, null) ?: return null
        return runCatching { json.decodeFromString<LeaderboardSnapshot>(raw) }.getOrNull()
    }

    companion object {
        private const val KEY_USER_ID = "ranking_user_id"
        private const val KEY_USERNAME = "ranking_username"
        private const val KEY_OPT_IN = "ranking_opt_in"
        private const val KEY_ENDPOINT_URL = "ranking_endpoint_url"
        private const val KEY_LAST_SYNC = "ranking_last_sync_millis"
        private const val KEY_CACHED_SNAPSHOT = "ranking_cached_snapshot"
    }
}
