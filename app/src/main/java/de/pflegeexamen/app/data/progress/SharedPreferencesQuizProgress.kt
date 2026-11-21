package de.pflegeexamen.app.data.progress

import android.content.Context

class SharedPreferencesQuizProgress(context: Context) : QuizProgressPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("quiz_practice", Context.MODE_PRIVATE)
    override fun load(date: String): String? = runCatching { prefs.getString(date, null) }.getOrNull()
    override fun save(date: String, value: String) { prefs.edit().putString(date, value).apply() }
    override fun remove(date: String) { prefs.edit().remove(date).apply() }
}
