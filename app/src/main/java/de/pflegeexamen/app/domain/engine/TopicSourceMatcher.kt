package de.pflegeexamen.app.domain.engine

import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import java.util.Locale

/**
 * Ordnet Textabschnitte bei Bedarf anhand der festen Themenbegriffe zu.
 * Originaldateien und die gespeicherten Textabschnitte werden dabei nie verändert.
 * Eine manuelle Quellenzuordnung darf die Suche nicht auf andere Dokumente einschränken:
 * auch später hinzugefügte Quellen müssen beim selben Thema gefunden werden können.
 */
object TopicSourceMatcher {
    private val genericKeywords = setOf(
        "anatomie", "physiologie", "pflege", "pflegeprozess", "pflegemassnahmen", "pflegemaßnahmen",
        "massnahmen", "maßnahmen", "symptome", "beobachtung", "definition", "grundbegriffe",
        "ursachen", "risiken", "prophylaxe", "prophylaxen", "beratung", "anleitung", "merksatze",
        "merksätze", "cave", "begrundungen", "begründungen", "ziele", "recht", "ethik", "allgemein"
    )

    fun match(
        chunks: List<ChunkInput>,
        title: String,
        keywords: List<String>,
        focusParameters: List<String> = emptyList(),
        detailDepth: DetailDepth = DetailDepth.STANDARD
    ): RetrievalOutcome {
        val specificKeywords = keywords.filterNot { it.trim().lowercase(Locale.GERMAN) in genericKeywords }
        return SourceRetriever.retrieve(
            allChunks = chunks,
            titleQuery = title,
            keywords = specificKeywords,
            focusParameters = focusParameters,
            // Der individuelle Hinweis enthält oft allgemeine Begriffe und darf deshalb
            // alleine keinen Treffer aus einem sachfremden Abschnitt auslösen.
            customPromptNote = "",
            selectedSourceIds = emptySet(),
            detailDepth = detailDepth
        )
    }
}
