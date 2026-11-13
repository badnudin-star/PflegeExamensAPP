package de.pflegeexamen.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LearningCategoryEntity::class,
        StudyProfileEntity::class,
        SourceDocumentEntity::class,
        SourceChunkEntity::class,
        PreparationItemEntity::class,
        OperatorItemEntity::class,
        StudyNoteEntity::class,
        FlashcardEntity::class,
        CaseStudyEntity::class,
        CaseQuestionEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun studyProfileDao(): StudyProfileDao
    abstract fun sourceDao(): SourceDao
    abstract fun preparationDao(): PreparationDao
    abstract fun operatorDao(): OperatorDao
    abstract fun studyNoteDao(): StudyNoteDao
    abstract fun flashcardDao(): FlashcardDao
    abstract fun caseStudyDao(): CaseStudyDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `learning_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `shortCode` TEXT NOT NULL,
                        `colorHex` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `study_profile` (
                        `id` INTEGER NOT NULL,
                        `totalXp` INTEGER NOT NULL,
                        `streakDays` INTEGER NOT NULL,
                        `freeDaysRemaining` INTEGER NOT NULL,
                        `dailyGoalPoints` INTEGER NOT NULL,
                        `todayPoints` INTEGER NOT NULL,
                        `lastActiveDayEpoch` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `source_documents` ADD COLUMN `categoryId` INTEGER")
                db.execSQL("ALTER TABLE `source_documents` ADD COLUMN `categoryLabel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `preparation_items` ADD COLUMN `categoryId` INTEGER")
                db.execSQL("ALTER TABLE `study_notes` ADD COLUMN `categoryId` INTEGER")
                db.execSQL("ALTER TABLE `study_notes` ADD COLUMN `categoryLabel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `flashcards` ADD COLUMN `categoryId` INTEGER")
                db.execSQL("ALTER TABLE `flashcards` ADD COLUMN `categoryLabel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `case_studies` ADD COLUMN `categoryId` INTEGER")
                db.execSQL("ALTER TABLE `case_studies` ADD COLUMN `categoryLabel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `case_questions` ADD COLUMN `aiFeedbackText` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `preparation_items` ADD COLUMN `examDayTag` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `preparation_items` ADD COLUMN `competenceFocusCodes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `operator_items` ADD COLUMN `exampleQuestion` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `operator_items` ADD COLUMN `expectationHorizon` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `operator_items` ADD COLUMN `commentNotes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `operator_items` ADD COLUMN `gradingRubric` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `operator_items` ADD COLUMN `caseRelationRule` TEXT NOT NULL DEFAULT 'OPTIONAL_CASE_RELATION'")
                db.execSQL("ALTER TABLE `case_questions` ADD COLUMN `operatorGradingRubric` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `case_questions` ADD COLUMN `operatorCommentNotes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `case_questions` ADD COLUMN `operatorCaseRelationRule` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pflege_examen_lokal.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
