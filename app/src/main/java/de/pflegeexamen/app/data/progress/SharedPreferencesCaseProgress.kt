package de.pflegeexamen.app.data.progress

import android.content.Context

/** Never expose the mutable string set owned by SharedPreferences. */
class SharedPreferencesCaseProgress(context: Context) : CaseProgressPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("case_progress", Context.MODE_PRIVATE)

    override fun loadCompleted(): Set<String> = prefs.getStringSet(KEY_COMPLETED, emptySet()).orEmpty().toSet()

    override fun saveCompleted(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_COMPLETED, ids.toSet()).apply()
    }

    private companion object { const val KEY_COMPLETED = "completed_case_ids" }
}
