package de.pflegeexamen.app.data.bundle

import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.PriorityLevel
import kotlinx.serialization.Serializable

@Serializable
data class BundleCategoryItem(
    val name: String,
    val shortCode: String,
    val colorHex: Long = 0xFF0E6B6EL,
    val description: String = ""
)

@Serializable
data class BundlePreparationItem(
    val title: String,
    val examDayTag: String = "",
    val competenceFocusCodes: String = "",
    val categoryOrCompetenceArea: String = "",
    val keywordsCsv: String = "",
    val detailDepth: String = DetailDepth.STANDARD.name,
    val priority: String = PriorityLevel.MEDIUM.name,
    val focusParametersCsv: String = "",
    val targetFlashcardCount: Int = 10,
    val flashcardTypesCsv: String = "DEFINITION,CAUSAL,LIST,CLOZE,CAVE",
    val customPromptNote: String = ""
)

@Serializable
data class BundleOperatorItem(
    val name: String,
    val requirementLevel: String = "",
    val description: String = "",
    val exampleQuestion: String = "",
    val expectationHorizon: String = "",
    val commentNotes: String = "",
    val gradingRubric: String = "",
    val caseRelationRule: String = CaseRelationRule.OPTIONAL_CASE_RELATION.name,
    val questionPattern: String = "",
    val structureType: String = OperatorStructureType.CAUSAL_REASONING.name,
    val defaultPoints: Int = 4
)

@Serializable
data class BundleSourceItem(
    val fileName: String = "",
    val title: String,
    val categoryShortCode: String = "",
    val tags: String = "",
    val inlineText: String = ""
)

@Serializable
data class BundleManifest(
    val bundleVersion: Int = 1,
    val bundleName: String = "PflegeExamen Gesamtpaket",
    val replaceExistingOperators: Boolean = false,
    val replaceExistingSources: Boolean = false,
    val replaceExistingPreparations: Boolean = false,
    val dailyGoalPoints: Int? = null,
    val rankingEndpointUrl: String? = null,
    val categories: List<BundleCategoryItem> = emptyList(),
    val preparations: List<BundlePreparationItem> = emptyList(),
    val operators: List<BundleOperatorItem> = emptyList(),
    val sources: List<BundleSourceItem> = emptyList()
)

data class BundleImportReport(
    val bundleName: String,
    val categoriesImported: Int,
    val preparationsImported: Int,
    val operatorsImported: Int,
    val sourcesImported: Int,
    val skippedSources: List<String> = emptyList()
)
