package de.pflegeexamen.app.data.local

import androidx.room.TypeConverter
import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.PriorityLevel
import de.pflegeexamen.app.domain.model.SelfEvaluation
import de.pflegeexamen.app.domain.model.SourceType

class Converters {
    @TypeConverter fun fromSourceType(v: SourceType): String = v.name
    @TypeConverter fun toSourceType(v: String): SourceType = SourceType.valueOf(v)

    @TypeConverter fun fromDetailDepth(v: DetailDepth): String = v.name
    @TypeConverter fun toDetailDepth(v: String): DetailDepth = DetailDepth.valueOf(v)

    @TypeConverter fun fromPriority(v: PriorityLevel): String = v.name
    @TypeConverter fun toPriority(v: String): PriorityLevel = PriorityLevel.valueOf(v)

    @TypeConverter fun fromFlashcardType(v: FlashcardType): String = v.name
    @TypeConverter fun toFlashcardType(v: String): FlashcardType = FlashcardType.valueOf(v)

    @TypeConverter fun fromMasteryStatus(v: MasteryStatus): String = v.name
    @TypeConverter fun toMasteryStatus(v: String): MasteryStatus = MasteryStatus.valueOf(v)

    @TypeConverter fun fromOperatorStructureType(v: OperatorStructureType): String = v.name
    @TypeConverter fun toOperatorStructureType(v: String): OperatorStructureType =
        OperatorStructureType.valueOf(v)

    @TypeConverter fun fromCaseRelationRule(v: CaseRelationRule): String = v.name
    @TypeConverter fun toCaseRelationRule(v: String): CaseRelationRule =
        runCatching { CaseRelationRule.valueOf(v) }.getOrDefault(CaseRelationRule.OPTIONAL_CASE_RELATION)

    @TypeConverter fun fromSelfEvaluation(v: SelfEvaluation): String = v.name
    @TypeConverter fun toSelfEvaluation(v: String): SelfEvaluation = SelfEvaluation.valueOf(v)
}
