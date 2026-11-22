package de.pflegeexamen.app.data.quiz

import kotlinx.coroutines.CancellationException

sealed interface RankedQuizStart {
    data class Ready(val quiz: RankedQuiz) : RankedQuizStart
    data class AlreadyRecorded(val date: String, val correct: Int, val total: Int) : RankedQuizStart
    data class Closed(val date: String) : RankedQuizStart
    /** Includes a recorded result awaiting acknowledgement, an unresolved marker, and storage errors. */
    data object Pending : RankedQuizStart
    data object Unavailable : RankedQuizStart
}

fun interface StartPendingCheck { suspend fun check(): PendingQuizState }
fun interface StartQuizLoader { suspend fun load(): RankedQuizLoad }
fun interface StartResultLookup { suspend fun lookup(date: String, token: String): AttemptLookup }

/** Check the local marker and the server before beginning; never modifies or submits an attempt. */
class RankedQuizStartCheck(
    private val pending: StartPendingCheck,
    private val loader: StartQuizLoader,
    private val vault: RankingTokenVault,
    private val lookup: StartResultLookup
) {
    suspend fun start(): RankedQuizStart {
        return try {
        if (pending.check() != PendingQuizState.None) return RankedQuizStart.Pending
        val quiz = (loader.load() as? RankedQuizLoad.Ready)?.quiz ?: return RankedQuizStart.Unavailable
        val token = (vault.load() as? TokenLoad.Available)?.token ?: return RankedQuizStart.Unavailable
        when (val result = lookup.lookup(quiz.date, token)) {
            AttemptLookup.NotFound -> RankedQuizStart.Ready(quiz)
            is AttemptLookup.Closed -> if (result.date == quiz.date) RankedQuizStart.Closed(result.date) else RankedQuizStart.Unavailable
            is AttemptLookup.Found -> if (result.date == quiz.date && result.total == 50 && result.correct in 0..50)
                RankedQuizStart.AlreadyRecorded(result.date, result.correct, result.total) else RankedQuizStart.Unavailable
            else -> RankedQuizStart.Unavailable
        }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { RankedQuizStart.Unavailable }
    }
}
