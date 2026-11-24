package de.pflegeexamen.app.data.progress

/** Caller-supplied data only. Null XML means unavailable, never a proven empty file. */
internal data class PreferenceEvidence(val memory: Map<String, *>, val xml: ByteArray?)

/**
 * Experimental comparison only; no paths, I/O, writes or app integration. Matching supplied
 * bytes and maps cannot establish the origin of those bytes, rule out a .bak file, establish
 * an atomic cross-store snapshot, or prove that data reached durable storage. The result is
 * deliberately still Unverified and must not be treated as a safe backup.
 */
internal object PracticeProgressEvidence {
    fun compare(cards: PreferenceEvidence, cases: PreferenceEvidence, quizzes: PreferenceEvidence): PracticeReadResult {
        return try {
            val cardXml = cards.xml ?: return PracticeReadResult.Unavailable
            val caseXml = cases.xml ?: return PracticeReadResult.Unavailable
            val quizXml = quizzes.xml ?: return PracticeReadResult.Unavailable
            if (!PreferenceXmlInspector.matches(cardXml, cards.memory) ||
                !PreferenceXmlInspector.matches(caseXml, cases.memory) ||
                !PreferenceXmlInspector.matches(quizXml, quizzes.memory)) {
                PracticeReadResult.InvalidData
            } else {
                PracticeProgressReader.readMaps(cards.memory, cases.memory, quizzes.memory)
            }
        } catch (_: Exception) {
            PracticeReadResult.Unavailable
        }
    }
}
