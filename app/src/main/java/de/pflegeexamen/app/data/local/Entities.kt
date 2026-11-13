package de.pflegeexamen.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.PriorityLevel
import de.pflegeexamen.app.domain.model.SelfEvaluation
import de.pflegeexamen.app.domain.model.SourceType

@Entity(tableName = "learning_categories")
data class LearningCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val shortCode: String,
    val colorHex: Long = 0xFF0E6B6EL,
    val description: String = "",
    val sortOrder: Int = 0
)

@Entity(tableName = "study_profile")
data class StudyProfileEntity(
    @PrimaryKey val id: Int = 1,
    val totalXp: Int = 0,
    val streakDays: Int = 0,
    val freeDaysRemaining: Int = 2,
    val dailyGoalPoints: Int = 20,
    val todayPoints: Int = 0,
    val lastActiveDayEpoch: Long = -1L
)

@Entity(tableName = "source_documents")
data class SourceDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceType: SourceType,
    val originalFileName: String? = null,
    val totalPagesOrSections: Int = 1,
    val characterCount: Int = 0,
    val tags: String = "",
    val categoryId: Long? = null,
    val categoryLabel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "source_chunks",
    foreignKeys = [
        ForeignKey(
            entity = SourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sourceId")]
)
data class SourceChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val chunkIndex: Int,
    val pageNumber: Int? = null,
    val sectionHeading: String = "Allgemein",
    val content: String,
    val normalizedTokens: String
)

@Entity(tableName = "preparation_items")
data class PreparationItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryOrCompetenceArea: String = "",
    val categoryId: Long? = null,
    val examDayTag: String = "",
    val competenceFocusCodes: String = "",
    val keywordsCsv: String = "",
    val detailDepth: DetailDepth = DetailDepth.STANDARD,
    val priority: PriorityLevel = PriorityLevel.MEDIUM,
    val focusParametersCsv: String = "",
    val targetFlashcardCount: Int = 10,
    val flashcardTypesCsv: String = "DEFINITION,CAUSAL,LIST,CLOZE,CAVE",
    val selectedSourceIdsCsv: String = "",
    val customPromptNote: String = "",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "operator_items")
data class OperatorItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val requirementLevel: String = "",
    val description: String = "",
    val exampleQuestion: String = "",
    val expectationHorizon: String = "",
    val commentNotes: String = "",
    val gradingRubric: String = "",
    val caseRelationRule: CaseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
    val questionPattern: String = "",
    val structureType: OperatorStructureType = OperatorStructureType.CAUSAL_REASONING,
    val defaultPoints: Int = 4,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "study_notes",
    foreignKeys = [
        ForeignKey(
            entity = PreparationItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["prepItemId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("prepItemId")]
)
data class StudyNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prepItemId: Long? = null,
    val categoryId: Long? = null,
    val categoryLabel: String = "",
    val title: String,
    val summaryMarkdown: String,
    val sectionsJson: String,
    val sourceSummary: String,
    val personalAnnotation: String = "",
    val masteryStatus: MasteryStatus = MasteryStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "flashcards",
    foreignKeys = [
        ForeignKey(
            entity = PreparationItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["prepItemId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = StudyNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["studyNoteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("prepItemId"), Index("studyNoteId"), Index("nextReviewAt")]
)
data class FlashcardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prepItemId: Long? = null,
    val studyNoteId: Long? = null,
    val categoryId: Long? = null,
    val categoryLabel: String = "",
    val topicTitle: String,
    val cardType: FlashcardType,
    val frontText: String,
    val backText: String,
    val citationLabel: String,
    val sourceExcerpt: String,
    val parameterTag: String = "",
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Float = 2.5f,
    val nextReviewAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "case_studies",
    foreignKeys = [
        ForeignKey(
            entity = PreparationItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["prepItemId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("prepItemId")]
)
data class CaseStudyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prepItemId: Long? = null,
    val categoryId: Long? = null,
    val categoryLabel: String = "",
    val vignetteText: String,
    val isExtractedOriginalCase: Boolean = false,
    val vignetteCitationsJson: String = "[]",
    val sourceBasisLabel: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "case_questions",
    foreignKeys = [
        ForeignKey(
            entity = CaseStudyEntity::class,
            parentColumns = ["id"],
            childColumns = ["caseStudyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("caseStudyId")]
)
data class CaseQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseStudyId: Long,
    val orderIndex: Int,
    val operatorId: Long? = null,
    val operatorName: String,
    val operatorLevel: String = "",
    val operatorHint: String = "",
    val operatorGradingRubric: String = "",
    val operatorCommentNotes: String = "",
    val operatorCaseRelationRule: String = "",
    val questionText: String,
    val modelAnswerText: String,
    val expectedKeyPointsJson: String = "[]",
    val citationsJson: String = "[]",
    val maxPoints: Int = 4,
    val userAnswerText: String = "",
    val isAnswerConfirmed: Boolean = false,
    val confirmedAt: Long? = null,
    val matchedKeyPointsCount: Int = 0,
    val totalKeyPointsCount: Int = 0,
    val matchedKeyPointsJson: String = "[]",
    val missingKeyPointsJson: String = "[]",
    val aiFeedbackText: String = "",
    val selfEvaluation: SelfEvaluation = SelfEvaluation.UNRATED
)
