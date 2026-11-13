package de.pflegeexamen.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.domain.model.SelfEvaluation
import kotlinx.coroutines.flow.Flow

data class ChunkWithSource(
    @Embedded val chunk: SourceChunkEntity,
    @Relation(
        parentColumn = "sourceId",
        entityColumn = "id"
    )
    val source: SourceDocumentEntity
)

data class CaseStudyWithQuestions(
    @Embedded val caseStudy: CaseStudyEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "caseStudyId"
    )
    val questions: List<CaseQuestionEntity>
)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM learning_categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LearningCategoryEntity>>

    @Query("SELECT * FROM learning_categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<LearningCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: LearningCategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<LearningCategoryEntity>)

    @Update
    suspend fun update(category: LearningCategoryEntity)

    @Delete
    suspend fun delete(category: LearningCategoryEntity)
}

@Dao
interface StudyProfileDao {
    @Query("SELECT * FROM study_profile WHERE id = 1")
    fun observeProfile(): Flow<StudyProfileEntity?>

    @Query("SELECT * FROM study_profile WHERE id = 1")
    suspend fun getProfile(): StudyProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: StudyProfileEntity)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_documents ORDER BY updatedAt DESC")
    fun observeAllSources(): Flow<List<SourceDocumentEntity>>

    @Query("SELECT * FROM source_documents ORDER BY updatedAt DESC")
    suspend fun getAllSources(): List<SourceDocumentEntity>

    @Query("SELECT * FROM source_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex ASC")
    fun observeChunksForSource(sourceId: Long): Flow<List<SourceChunkEntity>>

    @Query("SELECT * FROM source_chunks WHERE sourceId = :sourceId ORDER BY chunkIndex ASC")
    suspend fun getChunksForSource(sourceId: Long): List<SourceChunkEntity>

    @Transaction
    @Query("SELECT * FROM source_chunks ORDER BY sourceId ASC, chunkIndex ASC")
    suspend fun getAllChunksWithSource(): List<ChunkWithSource>

    @Query("""SELECT c.id AS chunkId, c.sourceId AS sourceId, s.title AS sourceTitle,
        s.sourceType AS sourceType, c.pageNumber AS pageNumber, c.sectionHeading AS sectionHeading,
        c.content AS content FROM source_chunks c JOIN source_documents s ON s.id = c.sourceId
        ORDER BY c.sourceId ASC, c.chunkIndex ASC""")
    suspend fun getChunkInputs(): List<ChunkInput>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: SourceDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<SourceChunkEntity>)

    @Query("UPDATE source_documents SET characterCount = :characterCount WHERE id = :id")
    suspend fun updateCharacterCount(id: Long, characterCount: Int)

    @Delete
    suspend fun deleteSource(source: SourceDocumentEntity)

    @Query(
        "UPDATE source_documents SET title = :title, tags = :tags, categoryId = :categoryId, categoryLabel = :categoryLabel, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateSourceMeta(
        id: Long,
        title: String,
        tags: String,
        categoryId: Long?,
        categoryLabel: String,
        updatedAt: Long
    )

    @Transaction
    suspend fun insertSourceWithChunks(
        source: SourceDocumentEntity,
        chunks: List<SourceChunkEntity>
    ): Long {
        val sourceId = insertSource(source)
        val linked = chunks.map { it.copy(sourceId = sourceId) }
        insertChunks(linked)
        return sourceId
    }
}

@Dao
interface PreparationDao {
    @Query("SELECT * FROM preparation_items ORDER BY examDayTag ASC, priority ASC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PreparationItemEntity>>

    @Query("SELECT * FROM preparation_items ORDER BY examDayTag ASC, priority ASC, title COLLATE NOCASE ASC")
    suspend fun getAll(): List<PreparationItemEntity>

    @Query("SELECT * FROM preparation_items WHERE id = :id")
    suspend fun getById(id: Long): PreparationItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PreparationItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PreparationItemEntity>)

    @Update
    suspend fun update(item: PreparationItemEntity)

    @Delete
    suspend fun delete(item: PreparationItemEntity)

    @Query("DELETE FROM preparation_items")
    suspend fun deleteAll()
}

@Dao
interface OperatorDao {
    @Query("SELECT * FROM operator_items ORDER BY requirementLevel ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<OperatorItemEntity>>

    @Query("SELECT * FROM operator_items ORDER BY requirementLevel ASC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<OperatorItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OperatorItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<OperatorItemEntity>)

    @Update
    suspend fun update(item: OperatorItemEntity)

    @Delete
    suspend fun delete(item: OperatorItemEntity)

    @Query("DELETE FROM operator_items")
    suspend fun deleteAll()
}

@Dao
interface StudyNoteDao {
    @Query("SELECT * FROM study_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StudyNoteEntity>>

    @Query("SELECT * FROM study_notes WHERE id = :id")
    fun observeById(id: Long): Flow<StudyNoteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: StudyNoteEntity): Long

    @Query("UPDATE study_notes SET personalAnnotation = :annotation, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAnnotation(id: Long, annotation: String, updatedAt: Long)

    @Query("UPDATE study_notes SET masteryStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMasteryStatus(id: Long, status: MasteryStatus, updatedAt: Long)

    @Delete
    suspend fun delete(note: StudyNoteEntity)
}

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM flashcards ORDER BY nextReviewAt ASC, createdAt DESC")
    fun observeAll(): Flow<List<FlashcardEntity>>

    @Query("SELECT * FROM flashcards WHERE nextReviewAt <= :nowMillis ORDER BY nextReviewAt ASC")
    fun observeDueCards(nowMillis: Long): Flow<List<FlashcardEntity>>

    @Query("SELECT COUNT(*) FROM flashcards WHERE nextReviewAt <= :nowMillis")
    fun observeDueCount(nowMillis: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<FlashcardEntity>): List<Long>

    @Update
    suspend fun update(card: FlashcardEntity)

    @Delete
    suspend fun delete(card: FlashcardEntity)

    @Query("DELETE FROM flashcards WHERE prepItemId = :prepItemId")
    suspend fun deleteByPrepItem(prepItemId: Long)
}

@Dao
interface CaseStudyDao {
    @Transaction
    @Query("SELECT * FROM case_studies ORDER BY createdAt DESC")
    fun observeAllWithQuestions(): Flow<List<CaseStudyWithQuestions>>

    @Transaction
    @Query("SELECT * FROM case_studies WHERE id = :caseStudyId")
    fun observeByIdWithQuestions(caseStudyId: Long): Flow<CaseStudyWithQuestions?>

    @Query("SELECT * FROM case_questions WHERE id = :questionId")
    suspend fun getQuestionById(questionId: Long): CaseQuestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaseStudy(caseStudy: CaseStudyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<CaseQuestionEntity>)

    @Update
    suspend fun updateQuestion(question: CaseQuestionEntity)

    @Query("UPDATE case_questions SET userAnswerText = :answerText WHERE id = :questionId AND isAnswerConfirmed = 0")
    suspend fun updateDraftAnswer(questionId: Long, answerText: String)

    @Query("UPDATE case_questions SET selfEvaluation = :evaluation WHERE id = :questionId")
    suspend fun updateSelfEvaluation(questionId: Long, evaluation: SelfEvaluation)

    @Delete
    suspend fun deleteCaseStudy(caseStudy: CaseStudyEntity)

    @Transaction
    suspend fun insertCaseStudyWithQuestions(
        caseStudy: CaseStudyEntity,
        questions: List<CaseQuestionEntity>
    ): Long {
        val caseId = insertCaseStudy(caseStudy)
        insertQuestions(questions.map { it.copy(caseStudyId = caseId) })
        return caseId
    }
}
