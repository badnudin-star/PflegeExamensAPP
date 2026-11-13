package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.RetrievedPassage
import de.pflegeexamen.app.domain.model.SentenceRole
import java.util.Locale
import kotlin.math.ln

/**
 * Strikt quellengebundener Offline-Retriever (BM25-inspiriert + deutsche Komposita- und
 * Parameter-Erkennung). Gibt NIEMALS erfundene Inhalte zurück: Wenn keine echte
 * Trefferbasis in den eigenen Quellen vorliegt, wird [RetrievalOutcome.NoMatches] geliefert.
 */
object SourceRetriever {

    private const val MIN_RELEVANCE_SCORE = 1.25

    fun retrieve(
        allChunks: List<ChunkInput>,
        titleQuery: String,
        keywords: List<String>,
        focusParameters: List<String> = emptyList(),
        customPromptNote: String = "",
        selectedSourceIds: Set<Long> = emptySet(),
        detailDepth: DetailDepth = DetailDepth.STANDARD
    ): RetrievalOutcome {
        val scopedChunks = if (selectedSourceIds.isEmpty()) {
            allChunks
        } else {
            allChunks.filter { it.sourceId in selectedSourceIds }
        }

        if (scopedChunks.isEmpty()) {
            return RetrievalOutcome.NoSources
        }

        val primaryTerms = buildQueryTerms(titleQuery, keywords)
        val extraTerms = buildQueryTerms(customPromptNote, emptyList())
        val paramTerms = focusParameters.flatMap { GermanTextAnalyzer.tokenizeKeywords(it, includeStems = false) }
            .distinct()

        val allSearchTerms = (primaryTerms + extraTerms).distinct()
        val effectiveTerms = if (allSearchTerms.isNotEmpty()) allSearchTerms else paramTerms

        if (effectiveTerms.isEmpty()) {
            return RetrievalOutcome.NoMatches(
                searchedQueryTerms = emptyList(),
                searchedSourceCount = scopedChunks.map { it.sourceId }.distinct().size
            )
        }

        val desiredRoles = mapParametersToRoles(focusParameters)

        // Precompute document token sets & document frequencies for IDF weighting
        val chunkIndex = scopedChunks.map { chunk ->
            val headingNorm = GermanTextAnalyzer.normalizeForMatching(chunk.sectionHeading)
            val contentNorm = GermanTextAnalyzer.normalizeForMatching("${chunk.sectionHeading}\n${chunk.content}")
            val tokenSet = GermanTextAnalyzer.tokenizeKeywords("${chunk.sectionHeading} ${chunk.content}").toSet()
            IndexedChunk(chunk, headingNorm, contentNorm, tokenSet)
        }

        val totalDocs = chunkIndex.size.toDouble()
        val docFreq = mutableMapOf<String, Int>()
        for (term in effectiveTerms) {
            val count = chunkIndex.count { ic ->
                GermanTextAnalyzer.matchesTerm(term, ic.tokens, ic.normalizedFullText)
            }
            docFreq[term] = count
        }

        val scored = mutableListOf<RetrievedPassage>()

        for (ic in chunkIndex) {
            var score = 0.0
            val matchedTerms = LinkedHashSet<String>()
            var primaryMatches = 0

            for (term in primaryTerms) {
                if (GermanTextAnalyzer.matchesTerm(term, ic.tokens, ic.normalizedFullText)) {
                    matchedTerms.add(term)
                    primaryMatches++
                    val df = (docFreq[term] ?: 1).coerceAtLeast(1)
                    val idf = ln((totalDocs + 1.0) / df) + 1.0
                    val headingBonus = if (GermanTextAnalyzer.matchesTerm(term, emptySet(), ic.normalizedHeading)) 1.6 else 0.0
                    score += (1.5 * idf) + headingBonus
                }
            }

            // Phrase match bonus for full topic title
            val titleNorm = GermanTextAnalyzer.normalizeForMatching(titleQuery.trim())
            if (titleNorm.length >= 4 && ic.normalizedFullText.contains(titleNorm)) {
                score += 2.5
                if (ic.normalizedHeading.contains(titleNorm)) score += 2.0
            }

            for (term in extraTerms) {
                if (GermanTextAnalyzer.matchesTerm(term, ic.tokens, ic.normalizedFullText)) {
                    matchedTerms.add(term)
                    score += 0.9
                }
            }

            // Strict ground-truth gate: require at least one genuine primary or extra query term match
            if (matchedTerms.isEmpty() || (primaryTerms.isNotEmpty() && primaryMatches == 0 && extraTerms.isEmpty())) {
                continue
            }

            val classified = DocumentChunker.classifyChunkSentences(ic.chunk.content)

            // Parameter & semantic role alignment bonus
            if (desiredRoles.isNotEmpty()) {
                val roleHitCount = classified.count { s -> s.roles.any { it in desiredRoles } }
                score += (roleHitCount.coerceAtMost(4)) * 0.35
            }
            for (pTerm in paramTerms) {
                if (GermanTextAnalyzer.matchesTerm(pTerm, ic.tokens, ic.normalizedFullText)) {
                    score += 0.45
                }
            }

            if (score >= MIN_RELEVANCE_SCORE) {
                scored.add(
                    RetrievedPassage(
                        chunk = ic.chunk,
                        score = score,
                        matchedTerms = matchedTerms,
                        classifiedSentences = classified
                    )
                )
            }
        }

        if (scored.isEmpty()) {
            return RetrievalOutcome.NoMatches(
                searchedQueryTerms = effectiveTerms,
                searchedSourceCount = scopedChunks.map { it.sourceId }.distinct().size
            )
        }

        val sorted = scored
            .sortedWith(
                compareByDescending<RetrievedPassage> { it.score }
                    .thenBy { it.chunk.sourceId }
                    .thenBy { it.chunk.chunkId }
            )
            .take(detailDepth.maxTotalPassages)

        return RetrievalOutcome.Found(sorted)
    }

    fun buildQueryTerms(title: String, keywords: List<String>): List<String> {
        val raw = buildList {
            if (title.isNotBlank()) addAll(GermanTextAnalyzer.tokenizeKeywords(title, includeStems = false))
            keywords.forEach { kw ->
                if (kw.isNotBlank()) addAll(GermanTextAnalyzer.tokenizeKeywords(kw, includeStems = false))
            }
        }
        return raw.distinct()
    }

    fun mapParametersToRoles(focusParameters: List<String>): Set<SentenceRole> {
        val roles = LinkedHashSet<SentenceRole>()
        for (param in focusParameters) {
            val lower = param.lowercase(Locale.GERMAN)
            when {
                lower.contains("definit") || lower.contains("begriff") || lower.contains("grundlag") ->
                    roles.add(SentenceRole.DEFINITION)
                lower.contains("ursach") || lower.contains("risiko") || lower.contains("ätiolog") ->
                    roles.add(SentenceRole.CAUSE_RISK)
                lower.contains("symptom") || lower.contains("beobacht") || lower.contains("assess") ||
                    lower.contains("wert") || lower.contains("skala") || lower.contains("stadi") ||
                    lower.contains("diagnost") || lower.contains("befund") ->
                    roles.add(SentenceRole.SYMPTOM_ASSESSMENT)
                lower.contains("maßnahm") || lower.contains("prophylax") || lower.contains("intervent") ||
                    lower.contains("pflegeplan") || lower.contains("berat") || lower.contains("anleit") ||
                    lower.contains("therap") || lower.contains("handlung") ->
                    roles.add(SentenceRole.INTERVENTION_MEASURE)
                lower.contains("begründ") || lower.contains("ziel") || lower.contains("zusammenhang") ||
                    lower.contains("pathophys") || lower.contains("wirk") ->
                    roles.add(SentenceRole.REASON_GOAL)
                lower.contains("merk") || lower.contains("cave") || lower.contains("warn") ||
                    lower.contains("komplikat") || lower.contains("kontraind") ->
                    roles.add(SentenceRole.CAVE_WARNING)
                lower.contains("recht") || lower.contains("ethik") || lower.contains("gesetz") ||
                    lower.contains("delegat") || lower.contains("dokument") ->
                    roles.add(SentenceRole.LEGAL_ETHICS)
            }
        }
        return roles
    }

    private data class IndexedChunk(
        val chunk: ChunkInput,
        val normalizedHeading: String,
        val normalizedFullText: String,
        val tokens: Set<String>
    )
}
