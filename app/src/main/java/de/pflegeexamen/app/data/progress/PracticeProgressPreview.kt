package de.pflegeexamen.app.data.progress

import de.pflegeexamen.app.domain.content.PublishedQuiz

/** A read-only comparison; these values are not an instruction to merge or overwrite. */
internal data class MarkingPreview(
    val inFile: Int,
    val alreadyOnDevice: Int?,
    val onlyOnDevice: Int?,
    val notInCatalog: Int?
)

internal enum class QuizContentMatch { MATCHES, DIFFERENT_OR_INVALID, UNAVAILABLE }
internal enum class QuizDeviceMatch { IDENTICAL, DIFFERENT, NOT_PRESENT, UNAVAILABLE }
internal data class QuizPreview(val date: String, val content: QuizContentMatch, val device: QuizDeviceMatch)
internal data class PracticePreview(
    val cards: MarkingPreview,
    val cases: MarkingPreview,
    val quizzes: List<QuizPreview>,
    val quizDaysOnlyOnDevice: Int?
)

internal sealed interface PreviewResult {
    data object InvalidFile : PreviewResult
    data object InvalidDeviceSnapshot : PreviewResult
    data class Ready(val preview: PracticePreview) : PreviewResult
}

/**
 * Pure prototype. Null device state means it could not be read, not that it is empty.
 * Null catalog sets mean unavailable; empty sets mean a successfully loaded empty catalog.
 * Quiz questions must be supplied in their original order from a validated source. Missing,
 * empty or date-mismatched quiz data cannot establish a match. A fingerprint match proves
 * neither authenticity nor future availability; matching card/case IDs alone do not prove
 * their content is unchanged. No I/O, storage modification or conflict resolution occurs.
 */
internal object PracticeProgressPreview {
    fun compare(
        fileBytes: ByteArray,
        device: PracticeProgressSnapshot?,
        catalogCardIds: Set<String>?,
        catalogCaseIds: Set<String>?,
        availableQuizzes: Map<String, List<PublishedQuiz>>
    ): PreviewResult {
        val file = PracticeProgressFormat.decode(fileBytes) ?: return PreviewResult.InvalidFile
        if (device != null && !PracticeProgressFormat.valid(device)) return PreviewResult.InvalidDeviceSnapshot
        val deviceDays = device?.quizzes?.associateBy { it.date }
        val fileDays = file.quizzes.map { it.date }.toSet()
        return PreviewResult.Ready(PracticePreview(
            cards = markings(file.learnedCardIds, device?.learnedCardIds, catalogCardIds),
            cases = markings(file.completedCaseIds, device?.completedCaseIds, catalogCaseIds),
            quizzes = file.quizzes.sortedBy { it.date }.map { quiz ->
                val questions = availableQuizzes[quiz.date]
                val content = when {
                    questions.isNullOrEmpty() || questions.any { it.published != quiz.date } -> QuizContentMatch.UNAVAILABLE
                    quiz.session.isValidFor(questions) -> QuizContentMatch.MATCHES
                    else -> QuizContentMatch.DIFFERENT_OR_INVALID
                }
                val current = deviceDays?.get(quiz.date)
                val deviceMatch = when {
                    deviceDays == null -> QuizDeviceMatch.UNAVAILABLE
                    current == null -> QuizDeviceMatch.NOT_PRESENT
                    current.session == quiz.session -> QuizDeviceMatch.IDENTICAL
                    else -> QuizDeviceMatch.DIFFERENT
                }
                QuizPreview(quiz.date, content, deviceMatch)
            },
            quizDaysOnlyOnDevice = deviceDays?.keys?.count { it !in fileDays }
        ))
    }

    private fun markings(inFile: List<String>, device: List<String>?, catalog: Set<String>?): MarkingPreview {
        val imported = inFile.toSet()
        val local = device?.toSet()
        return MarkingPreview(
            inFile = imported.size,
            alreadyOnDevice = local?.count { it in imported },
            onlyOnDevice = local?.count { it !in imported },
            notInCatalog = catalog?.let { known -> imported.count { it !in known } }
        )
    }
}
