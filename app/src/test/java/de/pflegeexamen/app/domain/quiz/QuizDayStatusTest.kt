package de.pflegeexamen.app.domain.quiz

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizDayStatusTest {
    @Test fun `day follows Berlin midnight rather than device zone including daylight saving`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(LocalDate.parse("2026-11-12"), berlinQuizDay(Clock.fixed(Instant.parse("2026-11-12T22:59:59Z"), tokyo)))
        assertEquals(LocalDate.parse("2026-11-13"), berlinQuizDay(Clock.fixed(Instant.parse("2026-11-12T23:00:00Z"), tokyo)))
        assertEquals(LocalDate.parse("2026-07-01"), berlinQuizDay(Clock.fixed(Instant.parse("2026-06-30T22:00:00Z"), tokyo)))
    }

    @Test fun `archive combines all past sources without duplicates or today and future`() {
        assertEquals(listOf("2026-11-12", "2026-11-11", "2026-11-10"),
            archiveQuizDates(listOf("2026-11-13", "2026-11-12", "2026-11-15"),
                listOf("2026-11-11", "2026-11-12"), listOf("2026-11-10", "broken"), "2026-11-13"))
        assertEquals(emptyList<String>(), archiveQuizDates(listOf("2026-11-12"), emptyList(), emptyList(), "invalid"))
    }

    @Test fun `status describes loaded content only and counts today's questions`() {
        val today = LocalDate.parse("2026-11-13")
        val missing = loadedQuizDayMessage(listOf("2026-11-12"), today)
        assertTrue(missing.contains("im geladenen Inhaltsstand keine Quizfragen"))
        assertEquals("Heute im geladenen Inhaltsstand: 1 Frage.", loadedQuizDayMessage(listOf("2026-11-13"), today))
        assertEquals("Heute im geladenen Inhaltsstand: 2 Fragen.", loadedQuizDayMessage(listOf("2026-11-13", "2026-11-12", "2026-11-13"), today))
    }
}
