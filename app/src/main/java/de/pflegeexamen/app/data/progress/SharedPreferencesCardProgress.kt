package de.pflegeexamen.app.data.progress

import android.content.Context

class SharedPreferencesCardProgress(context: Context) : CardProgressPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("card_progress", Context.MODE_PRIVATE)
    override fun loadLearned(): Set<String> = prefs.getStringSet(KEY_LEARNED, emptySet()).orEmpty().toSet()
    override fun saveLearned(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_LEARNED, ids.toSet()).apply()
    }
    private companion object { const val KEY_LEARNED = "learned_card_ids" }
}
