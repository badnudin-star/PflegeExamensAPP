package de.pflegeexamen.app.domain.quiz

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** The service uses Berlin calendar days, regardless of the device's timezone. */
fun berlinQuizDay(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(BERLIN))

/** This describes only locally loaded content, not whether a server has published anything. */
fun loadedQuizDayMessage(publishedDates: List<String>, today: LocalDate): String {
    val count = publishedDates.count { it == today.toString() }
    return if (count == 0) {
        "Für heute sind im geladenen Inhaltsstand keine Quizfragen enthalten. Falls vorhanden, kannst du ältere Quiztage weiter üben; die Inhalte lassen sich in den Einstellungen aktualisieren."
    } else {
        "Heute im geladenen Inhaltsstand: $count ${if (count == 1) "Frage" else "Fragen"}."
    }
}

/** Merge catalog, cached and online past days; the current day belongs in Heute. */
fun archiveQuizDates(catalogDates: Collection<String>, cachedDates: Collection<String>, onlineDates: Collection<String>, today: String): List<String> {
    fun valid(value: String) = runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
    if (!valid(today)) return emptyList()
    return (catalogDates + cachedDates + onlineDates).filter { valid(it) && it < today }.distinct().sortedDescending()
}
