package de.pflegeexamen.app.data.repository

import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import de.pflegeexamen.app.data.ai.AiCallResult
import de.pflegeexamen.app.data.ai.AiPreferencesStore
import de.pflegeexamen.app.data.ai.HttpAiTextClient
import de.pflegeexamen.app.data.ai.OnDeviceAiClient
import de.pflegeexamen.app.data.ai.OnDeviceAiStatus
import de.pflegeexamen.app.data.bundle.BundleCategoryItem
import de.pflegeexamen.app.data.bundle.BundleImportReport
import de.pflegeexamen.app.data.bundle.BundleManifest
import de.pflegeexamen.app.data.bundle.BundleOperatorItem
import de.pflegeexamen.app.data.bundle.BundlePreparationItem
import de.pflegeexamen.app.data.bundle.BundleSourceItem
import de.pflegeexamen.app.data.ingestion.DocumentIngestionService
import de.pflegeexamen.app.data.ingestion.ParsedImportResult
import de.pflegeexamen.app.data.local.AppDatabase
import de.pflegeexamen.app.data.local.CaseQuestionEntity
import de.pflegeexamen.app.data.local.CaseStudyEntity
import de.pflegeexamen.app.data.local.CaseStudyWithQuestions
import de.pflegeexamen.app.data.local.FlashcardEntity
import de.pflegeexamen.app.data.local.LearningCategoryEntity
import de.pflegeexamen.app.data.local.OperatorItemEntity
import de.pflegeexamen.app.data.local.PreparationItemEntity
import de.pflegeexamen.app.data.local.SourceChunkEntity
import de.pflegeexamen.app.data.local.SourceDocumentEntity
import de.pflegeexamen.app.data.local.StudyNoteEntity
import de.pflegeexamen.app.data.local.StudyProfileEntity
import de.pflegeexamen.app.data.ranking.HttpRankingClient
import de.pflegeexamen.app.data.ranking.RankingPreferencesStore
import de.pflegeexamen.app.data.ranking.RankingSyncResult
import de.pflegeexamen.app.domain.ai.AiJsonResponseParser
import de.pflegeexamen.app.domain.ai.AiPromptBuilder
import de.pflegeexamen.app.domain.engine.AnswerEvaluator
import de.pflegeexamen.app.domain.engine.CaseStudyGenerator
import de.pflegeexamen.app.domain.engine.DocumentChunker
import de.pflegeexamen.app.domain.engine.FlashcardGenerator
import de.pflegeexamen.app.domain.engine.GamificationEngine
import de.pflegeexamen.app.domain.engine.RankingEngine
import de.pflegeexamen.app.domain.engine.SourceRetriever
import de.pflegeexamen.app.domain.engine.SpacedRepetition
import de.pflegeexamen.app.domain.engine.StudyNoteGenerator
import de.pflegeexamen.app.domain.engine.StudyProfileState
import de.pflegeexamen.app.domain.model.AiSettings
import de.pflegeexamen.app.domain.model.CaseRelationRule
import de.pflegeexamen.app.domain.model.ChunkInput
import de.pflegeexamen.app.domain.model.DefaultCatalogs
import de.pflegeexamen.app.domain.model.DetailDepth
import de.pflegeexamen.app.domain.model.ExamRank
import de.pflegeexamen.app.domain.model.FlashcardType
import de.pflegeexamen.app.domain.model.LeaderboardSnapshot
import de.pflegeexamen.app.domain.model.MasteryStatus
import de.pflegeexamen.app.domain.model.OperatorPromptSpec
import de.pflegeexamen.app.domain.model.OperatorStructureType
import de.pflegeexamen.app.domain.model.PrepGenerationConfig
import de.pflegeexamen.app.domain.model.PriorityLevel
import de.pflegeexamen.app.domain.model.RankingSettings
import de.pflegeexamen.app.domain.model.RawPage
import de.pflegeexamen.app.domain.model.RetrievalOutcome
import de.pflegeexamen.app.domain.model.ScoreSubmissionPayload
import de.pflegeexamen.app.domain.model.SelfEvaluation
import de.pflegeexamen.app.domain.model.SourceCitation
import de.pflegeexamen.app.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface GenerationReport {
    data class Success(
        val studyNoteId: Long?,
        val studyNoteTitle: String,
        val flashcardCount: Int,
        val matchedPassageCount: Int,
        val sourceSummary: String,
        val engineUsedLabel: String,
        val infoHint: String? = null
    ) : GenerationReport

    data class NoEvidenceFound(
        val message: String
    ) : GenerationReport
}

sealed interface CaseGenerationReport {
    data class Success(
        val caseStudyId: Long,
        val questionCount: Int,
        val engineUsedLabel: String,
        val skippedOperatorsInfo: List<String>,
        val fallbackNote: String? = null
    ) : CaseGenerationReport

    data class NoEvidenceFound(
        val message: String
    ) : CaseGenerationReport
}

@Serializable
data class PreparationExportItem(
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
data class OperatorExportItem(
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

class PflegeRepository(
    private val db: AppDatabase,
    val ingestionService: DocumentIngestionService,
    private val aiPreferencesStore: AiPreferencesStore,
    private val aiClient: HttpAiTextClient,
    private val onDeviceAiClient: OnDeviceAiClient,
    private val rankingPreferencesStore: RankingPreferencesStore,
    private val rankingClient: HttpRankingClient
) {
    private val categoryDao = db.categoryDao()
    private val profileDao = db.studyProfileDao()
    private val sourceDao = db.sourceDao()
    private val preparationDao = db.preparationDao()
    private val operatorDao = db.operatorDao()
    private val studyNoteDao = db.studyNoteDao()
    private val flashcardDao = db.flashcardDao()
    private val caseStudyDao = db.caseStudyDao()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    val aiSettings: StateFlow<AiSettings> = aiPreferencesStore.settings
    val rankingSettings: StateFlow<RankingSettings> = rankingPreferencesStore.settings
    val cachedLeaderboard: StateFlow<LeaderboardSnapshot?> = rankingPreferencesStore.cachedSnapshot

    val categories: Flow<List<LearningCategoryEntity>> = categoryDao.observeAll().map { categories -> categories.filterNot { it.shortCode.equals("Ana/Phys", true) } }
    val studyProfile: Flow<StudyProfileEntity?> = profileDao.observeProfile()
    val sources: Flow<List<SourceDocumentEntity>> = sourceDao.observeAllSources()
    val preparationItems: Flow<List<PreparationItemEntity>> = preparationDao.observeAll().map { items ->
        val titles = DefaultCatalogs.defaultPreparations.map { it.title.lowercase() }.toSet()
        items.filter { it.title.trim().lowercase() in titles }
    }
    val operators: Flow<List<OperatorItemEntity>> = operatorDao.observeAll()
    val studyNotes: Flow<List<StudyNoteEntity>> = studyNoteDao.observeAll()
    val flashcards: Flow<List<FlashcardEntity>> = flashcardDao.observeAll()
    val caseStudies: Flow<List<CaseStudyWithQuestions>> = caseStudyDao.observeAllWithQuestions()

    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (profileDao.getProfile() == null) {
            profileDao.upsert(StudyProfileEntity())
        }
        val seededVer = aiPreferencesStore.getLastSeededVersionCode()
        if (!aiPreferencesStore.isInitialCatalogSeeded() || seededVer < CURRENT_CATALOG_SEED_VERSION) {
            restoreMissingDefaultCategories()
            restoreMissingDefaultOperators()
            restoreMissingDefaultPreparations()
            enforceClinicalFocus()
            aiPreferencesStore.markInitialCatalogSeeded()
            aiPreferencesStore.setLastSeededVersionCode(CURRENT_CATALOG_SEED_VERSION)
        }
        seedFromBundledAssetsIfPresent()
    }

    private suspend fun seedFromBundledAssetsIfPresent() {
        val manifestBytes = ingestionService.readAssetBytesOrNull("seed_bundle/manifest.json") ?: return
        val manifestText = manifestBytes.toString(StandardCharsets.UTF_8)
        val manifest = runCatching { json.decodeFromString<BundleManifest>(manifestText) }.getOrNull() ?: return
        val bundleTag = "${manifest.bundleName}:${manifest.bundleVersion}"
        if (aiPreferencesStore.getLastSeedBundleTag() == bundleTag) return

        importBundleManifestInternal(manifest) { assetRelPath ->
            val normalized = assetRelPath.trimStart('/')
            ingestionService.readAssetBytesOrNull("seed_bundle/$normalized")
                ?: ingestionService.readAssetBytesOrNull("seed_bundle/sources/${normalized.substringAfterLast('/')}")
        }
        aiPreferencesStore.setLastSeedBundleTag(bundleTag)
    }

    suspend fun restoreMissingDefaultOperators(): Int = withContext(Dispatchers.IO) {
        val existing = operatorDao.getAll()
        val byLowerName = existing.associateBy { it.name.trim().lowercase() }
        var addedOrUpgraded = 0

        for (t in DefaultCatalogs.defaultOperators) {
            val match = byLowerName[t.name.trim().lowercase()]
            if (match == null) {
                operatorDao.insert(
                    OperatorItemEntity(
                        name = t.name,
                        requirementLevel = t.requirementLevel,
                        description = t.description,
                        exampleQuestion = t.exampleQuestion,
                        expectationHorizon = t.expectationHorizon,
                        commentNotes = t.commentNotes,
                        gradingRubric = t.gradingRubric,
                        caseRelationRule = t.caseRelationRule,
                        questionPattern = t.questionPattern,
                        structureType = t.structureType,
                        defaultPoints = t.defaultPoints
                    )
                )
                addedOrUpgraded++
            } else if (match.expectationHorizon.isBlank() && match.gradingRubric.isBlank()) {
                // Upgrade v2.0-Basisoperatoren automatisch um Landes-AG Metadaten
                operatorDao.update(
                    match.copy(
                        requirementLevel = t.requirementLevel,
                        description = t.description,
                        exampleQuestion = t.exampleQuestion,
                        expectationHorizon = t.expectationHorizon,
                        commentNotes = t.commentNotes,
                        gradingRubric = t.gradingRubric,
                        caseRelationRule = t.caseRelationRule,
                        questionPattern = t.questionPattern,
                        structureType = t.structureType,
                        defaultPoints = t.defaultPoints
                    )
                )
                addedOrUpgraded++
            }
        }
        addedOrUpgraded
    }

    suspend fun restoreMissingDefaultPreparations(): Int = withContext(Dispatchers.IO) {
        val categories = categoryDao.getAll()
        val existing = preparationDao.getAll()
        val existingTitles = existing.map { it.title.trim().lowercase() }.toSet()
        val toInsert = mutableListOf<PreparationItemEntity>()

        for (t in DefaultCatalogs.defaultPreparations) {
            if (t.title.trim().lowercase() !in existingTitles) {
                val matchedCat = categories.firstOrNull {
                    it.shortCode.equals(t.categoryShortCode, ignoreCase = true) ||
                        it.name.contains(t.examDayTag, ignoreCase = true)
                }
                toInsert.add(
                    PreparationItemEntity(
                        title = t.title,
                        categoryOrCompetenceArea = matchedCat?.name ?: t.examDayTag,
                        categoryId = matchedCat?.id,
                        examDayTag = t.examDayTag,
                        competenceFocusCodes = t.competenceFocusCodes,
                        keywordsCsv = t.keywordsCsv,
                        detailDepth = t.detailDepth,
                        priority = t.priority,
                        focusParametersCsv = t.focusParameters().joinToString(", "),
                        targetFlashcardCount = if (t.detailDepth == DetailDepth.EXAM_DEEP) 12 else 8,
                        flashcardTypesCsv = "DEFINITION,CAUSAL,LIST,CLOZE,CAVE",
                        customPromptNote = t.customPromptNote
                    )
                )
            }
        }
        if (toInsert.isNotEmpty()) {
            preparationDao.insertAll(toInsert)
        }
        toInsert.size
    }

    // Bestehende Lernstände und Quellenzuordnungen bleiben dabei erhalten.
    private suspend fun enforceClinicalFocus() {
        val templates = DefaultCatalogs.defaultPreparations.associateBy { it.title.lowercase() }
        preparationDao.getAll().forEach { item ->
            val template = templates[item.title.trim().lowercase()] ?: return@forEach
            if (template.isClinicalPicture && !item.focusParametersCsv.contains("Anatomie & Physiologie", ignoreCase = true)) {
                preparationDao.update(item.copy(focusParametersCsv = (listOf("Anatomie & Physiologie") + item.focusParametersCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }).joinToString(", ")))
            }
        }
    }

    suspend fun restoreMissingDefaultCategories(): Int = withContext(Dispatchers.IO) {
        val existing = categoryDao.getAll().map { it.shortCode.trim().lowercase() to it.name.trim().lowercase() }
        val existingNames = existing.map { it.second }.toSet()
        val existingCodes = existing.map { it.first }.toSet()
        val missing = DefaultCatalogs.defaultCategories.filter {
            it.name.trim().lowercase() !in existingNames &&
                it.shortCode.trim().lowercase() !in existingCodes
        }
        if (missing.isNotEmpty()) {
            val baseOrder = existing.size
            categoryDao.insertAll(
                missing.mapIndexed { idx, t ->
                    LearningCategoryEntity(
                        name = t.name,
                        shortCode = t.shortCode,
                        colorHex = t.colorHex,
                        description = t.description,
                        sortOrder = baseOrder + idx
                    )
                }
            )
        }
        missing.size
    }

    suspend fun importBundleZip(uri: Uri): BundleImportReport = withContext(Dispatchers.IO) {
        val fileEntries = mutableMapOf<String, ByteArray>()
        var manifestRaw: String? = null

        ingestionService.openUriInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = entry.name.replace('\\', '/').trimStart('/')
                        if (!safeName.contains("..")) {
                            val bytes = zis.readBytes()
                            if (safeName.equals("manifest.json", ignoreCase = true) ||
                                safeName.endsWith("/manifest.json", ignoreCase = true)
                            ) {
                                manifestRaw = bytes.toString(StandardCharsets.UTF_8)
                            } else {
                                fileEntries[safeName.lowercase()] = bytes
                                val baseName = safeName.substringAfterLast('/').lowercase()
                                fileEntries.putIfAbsent(baseName, bytes)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Bundle-ZIP konnte nicht geöffnet werden.")

        val rawJson = manifestRaw
            ?: throw IllegalArgumentException("Im Archiv fehlt die manifest.json (erstellt z. B. mit PflegeBundleStudio.html).")
        val manifest = json.decodeFromString<BundleManifest>(rawJson)

        importBundleManifestInternal(manifest) { relPath ->
            val key = relPath.replace('\\', '/').trimStart('/').lowercase()
            fileEntries[key] ?: fileEntries[key.substringAfterLast('/')]
        }
    }

    private suspend fun importBundleManifestInternal(
        manifest: BundleManifest,
        resolveFileBytes: (String) -> ByteArray?
    ): BundleImportReport {
        if (manifest.dailyGoalPoints != null) {
            updateDailyGoal(manifest.dailyGoalPoints)
        }
        val manifestEndpoint = manifest.rankingEndpointUrl?.trim().orEmpty()
        if (manifestEndpoint.startsWith("http://") || manifestEndpoint.startsWith("https://")) {
            val cur = rankingPreferencesStore.currentSettings()
            rankingPreferencesStore.updateSettings(
                username = cur.username,
                rankingOptIn = cur.rankingOptIn,
                endpointUrl = manifestEndpoint
            )
        }

        // 1. Kategorien abgleichen / anlegen
        var catCount = 0
        val existingCats = categoryDao.getAll().toMutableList()
        for ((idx, c) in manifest.categories.withIndex()) {
            if (c.name.isBlank() || c.shortCode.isBlank() || c.shortCode.equals("Ana/Phys", true)) continue
            val exists = existingCats.any {
                it.shortCode.equals(c.shortCode.trim(), ignoreCase = true) ||
                    it.name.equals(c.name.trim(), ignoreCase = true)
            }
            if (!exists) {
                val id = categoryDao.insert(
                    LearningCategoryEntity(
                        name = c.name.trim(),
                        shortCode = c.shortCode.trim(),
                        colorHex = c.colorHex,
                        description = c.description.trim(),
                        sortOrder = existingCats.size + idx
                    )
                )
                existingCats.add(
                    LearningCategoryEntity(
                        id = id,
                        name = c.name.trim(),
                        shortCode = c.shortCode.trim(),
                        colorHex = c.colorHex,
                        description = c.description.trim(),
                        sortOrder = existingCats.size + idx
                    )
                )
                catCount++
            }
        }
        val allCats = categoryDao.getAll()

        // 2. Operatoren importieren / aktualisieren
        var opCount = 0
        if (manifest.replaceExistingOperators) {
            operatorDao.deleteAll()
        }
        val currentOps = operatorDao.getAll().associateBy { it.name.trim().lowercase() }.toMutableMap()
        for (op in manifest.operators) {
            if (op.name.isBlank()) continue
            val st = runCatching { OperatorStructureType.valueOf(op.structureType.trim()) }
                .getOrDefault(OperatorStructureType.CAUSAL_REASONING)
            val rule = runCatching { CaseRelationRule.valueOf(op.caseRelationRule.trim()) }
                .getOrDefault(CaseRelationRule.OPTIONAL_CASE_RELATION)
            val key = op.name.trim().lowercase()
            val prev = currentOps[key]
            val entity = OperatorItemEntity(
                id = prev?.id ?: 0L,
                name = op.name.trim(),
                requirementLevel = op.requirementLevel.trim(),
                description = op.description.trim(),
                exampleQuestion = op.exampleQuestion.trim(),
                expectationHorizon = op.expectationHorizon.trim(),
                commentNotes = op.commentNotes.trim(),
                gradingRubric = op.gradingRubric.trim(),
                caseRelationRule = rule,
                questionPattern = op.questionPattern.trim(),
                structureType = st,
                defaultPoints = op.defaultPoints.coerceIn(1, 30)
            )
            if (prev == null) {
                val id = operatorDao.insert(entity)
                currentOps[key] = entity.copy(id = id)
            } else {
                operatorDao.update(entity)
                currentOps[key] = entity
            }
            opCount++
        }

        // Themen sind fest vorgegeben. Alte Pakete können sie weder ersetzen noch erweitern.
        val prepCount = 0
        restoreMissingDefaultPreparations()

        // 4. Quellen einlesen (inlineText oder Datei aus ZIP/Assets)
        var sourceCount = 0
        val skippedSources = mutableListOf<String>()
        try {
        db.withTransaction {
        val previousSources = sourceDao.getAllSources()
        // Erst neue Quellen einlesen; vorhandene Daten nur nach vollständig erfolgreichem
        // Import entfernen. Fehlende oder defekte Dateien dürfen nie Daten löschen.
        val existingSourceTitles = (if (manifest.replaceExistingSources) emptyList() else previousSources)
            .associate { it.title.trim().lowercase() to it.id }.toMutableMap()
        val topics = preparationDao.getAll().filter { item ->
            DefaultCatalogs.defaultPreparations.any { it.title.equals(item.title, true) }
        }
        if (manifest.replaceExistingSources) {
            topics.forEach { preparationDao.update(it.copy(selectedSourceIdsCsv = "")) }
        }
        val topicByTitle = topics.associateBy { it.title.trim().lowercase() }.mapValues { (_, topic) ->
            if (manifest.replaceExistingSources) topic.copy(selectedSourceIdsCsv = "") else topic
        }.toMutableMap()
        suspend fun assignTopics(sourceId: Long, titles: List<String>?) {
            if (titles == null) return // Legacy-Paket: bestehende Zuordnungen nicht verändern.
            topicByTitle.toMap().forEach { (key, topic) ->
                val ids = topic.selectedSourceIdsCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it != sourceId }
                val updated = topic.copy(selectedSourceIdsCsv = ids.joinToString(","))
                preparationDao.update(updated)
                topicByTitle[key] = updated
            }
            titles.distinct().forEach { title ->
                val key = title.trim().lowercase()
                val topic = topicByTitle[key]
                if (topic == null) {
                    skippedSources.add("Unbekanntes Thema: $title")
                } else {
                    val ids = topic.selectedSourceIdsCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
                    ids.add(sourceId)
                    val updated = topic.copy(selectedSourceIdsCsv = ids.sorted().joinToString(","))
                    preparationDao.update(updated)
                    topicByTitle[key] = updated
                }
            }
        }

        for (src in manifest.sources) {
            if (src.title.isBlank() && src.fileName.isBlank()) {
                skippedSources.add("Quelle ohne Titel oder Datei")
                continue
            }
            val matchedCat = allCats.firstOrNull {
                it.shortCode.equals(src.categoryShortCode.trim(), ignoreCase = true) ||
                    it.name.equals(src.categoryShortCode.trim(), ignoreCase = true)
            }
            val resolvedTitle = src.title.ifBlank { src.fileName.substringAfterLast('/').substringBeforeLast('.') }
            val previous = existingSourceTitles[resolvedTitle.trim().lowercase()]
            if (previous != null) {
                assignTopics(previous, src.topicTitles)
                skippedSources.add("$resolvedTitle (bereits vorhanden; Themenzuordnung aktualisiert)")
                continue
            }

            if (src.inlineText.isNotBlank()) {
                val pages = ingestionService.splitPlainTextToLogicalSections(src.inlineText)
                val sourceId = saveRawPagesAsSource(
                    title = resolvedTitle,
                    tags = src.tags,
                    sourceType = SourceType.TXT,
                    originalFileName = src.fileName.ifBlank { "$resolvedTitle.txt" },
                    pages = pages,
                    categoryId = matchedCat?.id,
                    categoryLabel = matchedCat?.name ?: src.categoryShortCode
                )
                assignTopics(sourceId, src.topicTitles)
                existingSourceTitles[resolvedTitle.trim().lowercase()] = sourceId
                sourceCount++
                continue
            }

            val bytes = if (src.fileName.isNotBlank()) resolveFileBytes(src.fileName) else null
            if (bytes == null) {
                skippedSources.add("${resolvedTitle} (Datei nicht im Paket gefunden)")
                continue
            }

            runCatching {
                val parsed = ingestionService.importDocumentFromBytes(src.fileName, bytes)
                saveParsedImport(
                    title = resolvedTitle,
                    tags = src.tags,
                    parsed = parsed,
                    categoryId = matchedCat?.id,
                    categoryLabel = matchedCat?.name ?: src.categoryShortCode
                )
            }.onSuccess { sourceId ->
                assignTopics(sourceId, src.topicTitles)
                existingSourceTitles[resolvedTitle.trim().lowercase()] = sourceId
                sourceCount++
            }.onFailure { err ->
                skippedSources.add("$resolvedTitle (${err.message ?: "Importfehler"})")
            }
        }

        if (manifest.replaceExistingSources && skippedSources.isEmpty() && sourceCount == manifest.sources.size) {
            previousSources.forEach { sourceDao.deleteSource(it) }
        } else if (manifest.replaceExistingSources) {
            throw IllegalStateException("Quellenersetzung unvollständig")
        }
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sourceCount = 0
            skippedSources.add("Quellenimport zurückgerollt; vorhandene Quellen bleiben erhalten: ${e.message.orEmpty()}")
        }

        return BundleImportReport(
            bundleName = manifest.bundleName,
            categoriesImported = catCount,
            preparationsImported = prepCount,
            operatorsImported = opCount,
            sourcesImported = sourceCount,
            skippedSources = skippedSources
        )
    }

    suspend fun exportBundleZip(uri: Uri): BundleImportReport = withContext(Dispatchers.IO) {
        val cats = categoryDao.getAll()
        val preps = preparationDao.getAll()
        val ops = operatorDao.getAll()
        val docs = sourceDao.getAllSources()
        val profile = profileDao.getProfile()

        val sourceItems = mutableListOf<BundleSourceItem>()
        val sourceFilePayloads = mutableListOf<Pair<String, ByteArray>>()

        docs.forEachIndexed { idx, doc ->
            val chunks = sourceDao.getChunksForSource(doc.id)
            val fullText = chunks.joinToString("\n\n") { chunk ->
                val pageTag = chunk.pageNumber?.let { "[Seite $it] " }.orEmpty()
                val heading = chunk.sectionHeading.takeIf { it.isNotBlank() && it != "Allgemein" }
                    ?.let { "### $it\n" }.orEmpty()
                "$pageTag$heading${chunk.content}"
            }
            val safeFileBase = doc.title.replace(Regex("""[^a-zA-Z0-9äöüÄÖÜß_-]"""), "_").take(40).ifBlank { "quelle" }
            val relFileName = "sources/${idx + 1}_$safeFileBase.txt"
            val matchedCat = cats.firstOrNull { it.id == doc.categoryId }
            sourceItems.add(
                BundleSourceItem(
                    fileName = relFileName,
                    title = doc.title,
                    categoryShortCode = matchedCat?.shortCode ?: doc.categoryLabel,
                    tags = doc.tags,
                    topicTitles = preps.filter { prep -> prep.selectedSourceIdsCsv.split(",").any { it.trim().toLongOrNull() == doc.id } }.map { it.title }
                )
            )
            sourceFilePayloads.add(relFileName to fullText.toByteArray(StandardCharsets.UTF_8))
        }

        val manifest = BundleManifest(
            bundleVersion = 1,
            bundleName = "PflegeExamen Export",
            dailyGoalPoints = profile?.dailyGoalPoints,
            rankingEndpointUrl = rankingPreferencesStore.currentSettings().endpointUrl.ifBlank { null },
            categories = cats.map {
                BundleCategoryItem(
                    name = it.name,
                    shortCode = it.shortCode,
                    colorHex = it.colorHex,
                    description = it.description
                )
            },
            preparations = preps.map {
                BundlePreparationItem(
                    title = it.title,
                    examDayTag = it.examDayTag,
                    competenceFocusCodes = it.competenceFocusCodes,
                    categoryOrCompetenceArea = it.categoryOrCompetenceArea,
                    keywordsCsv = it.keywordsCsv,
                    detailDepth = it.detailDepth.name,
                    priority = it.priority.name,
                    focusParametersCsv = it.focusParametersCsv,
                    targetFlashcardCount = it.targetFlashcardCount,
                    flashcardTypesCsv = it.flashcardTypesCsv,
                    customPromptNote = it.customPromptNote
                )
            },
            operators = ops.map {
                BundleOperatorItem(
                    name = it.name,
                    requirementLevel = it.requirementLevel,
                    description = it.description,
                    exampleQuestion = it.exampleQuestion,
                    expectationHorizon = it.expectationHorizon,
                    commentNotes = it.commentNotes,
                    gradingRubric = it.gradingRubric,
                    caseRelationRule = it.caseRelationRule.name,
                    questionPattern = it.questionPattern,
                    structureType = it.structureType.name,
                    defaultPoints = it.defaultPoints
                )
            },
            sources = sourceItems
        )

        ingestionService.openUriOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                for ((path, payload) in sourceFilePayloads) {
                    zos.putNextEntry(ZipEntry(path))
                    zos.write(payload)
                    zos.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("Ziel-ZIP konnte nicht geschrieben werden.")

        BundleImportReport(
            bundleName = manifest.bundleName,
            categoriesImported = cats.size,
            preparationsImported = preps.size,
            operatorsImported = ops.size,
            sourcesImported = sourceItems.size
        )
    }

    suspend fun saveCategory(category: LearningCategoryEntity): Long = withContext(Dispatchers.IO) {
        if (category.id == 0L) {
            categoryDao.insert(category)
        } else {
            categoryDao.update(category)
            category.id
        }
    }

    suspend fun deleteCategory(category: LearningCategoryEntity) = withContext(Dispatchers.IO) {
        categoryDao.delete(category)
    }

    fun updateAiSettings(newSettings: AiSettings) {
        aiPreferencesStore.updateSettings(newSettings)
    }

    suspend fun testAiConnection(settings: AiSettings): AiCallResult =
        if (settings.isOnDevice()) onDeviceAiClient.testConnection() else aiClient.testConnection(settings)

    suspend fun smartphoneAiStatus(): OnDeviceAiStatus = onDeviceAiClient.checkStatus()
    fun downloadSmartphoneAi() = onDeviceAiClient.startBackgroundDownload()

    suspend fun saveUsernameAndSync(rawUsername: String): RankingSyncResult {
        val clean = RankingEngine.sanitizeUsername(rawUsername)
        rankingPreferencesStore.updateUsername(clean)
        return syncRanking(forceEvenIfNotDue = true)
    }

    fun updateRankingSettings(
        username: String,
        rankingOptIn: Boolean,
        endpointUrl: String
    ) {
        val clean = RankingEngine.sanitizeUsername(username)
        rankingPreferencesStore.updateSettings(
            username = clean,
            rankingOptIn = rankingOptIn,
            endpointUrl = endpointUrl
        )
    }

    suspend fun testRankingConnection(endpointUrl: String): Pair<Boolean, String> =
        rankingClient.testConnection(endpointUrl)

    suspend fun syncRanking(forceEvenIfNotDue: Boolean = false): RankingSyncResult =
        withContext(Dispatchers.IO) {
            val settings = rankingPreferencesStore.currentSettings()
            val cached = rankingPreferencesStore.currentSnapshot()
            if (!settings.isEndpointConfigured()) {
                return@withContext RankingSyncResult.NotConfigured
            }
            if (!forceEvenIfNotDue && !RankingEngine.isHourlySyncDue(settings.lastSyncMillis, cached)) {
                val snap = cached ?: return@withContext RankingSyncResult.NotConfigured
                return@withContext RankingSyncResult.Success(
                    snapshot = snap,
                    statusMessage = "Aktuelles stündliches Ranking ist noch gültig."
                )
            }

            val profile = profileDao.getProfile() ?: StudyProfileEntity()
            val payload = ScoreSubmissionPayload(
                userId = settings.userId,
                username = settings.username.ifBlank { "Azubi" },
                totalXp = profile.totalXp,
                streakDays = profile.streakDays,
                rankTitle = ExamRank.forXp(profile.totalXp).title
            )
            val result = rankingClient.syncWithEndpoint(settings, payload)
            if (result is RankingSyncResult.Success) {
                rankingPreferencesStore.saveSyncResult(result.snapshot)
            }
            result
        }

    suspend fun updateDailyGoal(goalPoints: Int) = withContext(Dispatchers.IO) {
        val current = profileDao.getProfile() ?: StudyProfileEntity()
        profileDao.upsert(current.copy(dailyGoalPoints = goalPoints.coerceIn(5, 100)))
    }

    fun observeSourceChunks(sourceId: Long): Flow<List<SourceChunkEntity>> =
        sourceDao.observeChunksForSource(sourceId)

    fun observeStudyNote(id: Long): Flow<StudyNoteEntity?> = studyNoteDao.observeById(id)

    fun observeDueFlashcardCount(nowMillis: Long = System.currentTimeMillis()): Flow<Int> =
        flashcardDao.observeDueCount(nowMillis)

    fun observeCaseStudy(id: Long): Flow<CaseStudyWithQuestions?> =
        caseStudyDao.observeByIdWithQuestions(id)

    suspend fun saveParsedImport(
        title: String,
        tags: String,
        parsed: ParsedImportResult,
        categoryId: Long? = null,
        categoryLabel: String = ""
    ): Long = saveRawPagesAsSource(
        title = title.ifBlank { parsed.suggestedTitle },
        tags = tags,
        sourceType = parsed.sourceType,
        originalFileName = parsed.originalFileName,
        pages = parsed.pages,
        categoryId = categoryId,
        categoryLabel = categoryLabel
    )

    suspend fun saveRawPagesAsSource(
        title: String,
        tags: String,
        sourceType: SourceType,
        originalFileName: String?,
        pages: List<RawPage>,
        categoryId: Long? = null,
        categoryLabel: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val draftChunks = DocumentChunker.chunkPages(pages)
        if (draftChunks.isEmpty()) {
            throw IllegalArgumentException(
                "Aus der Quelle konnte kein verwertbarer Text extrahiert werden. Bitte prüfen Sie die Datei oder das Foto."
            )
        }
        val totalChars = draftChunks.sumOf { it.content.length }
        val now = System.currentTimeMillis()
        val doc = SourceDocumentEntity(
            title = title.trim(),
            sourceType = sourceType,
            originalFileName = originalFileName,
            totalPagesOrSections = pages.size.coerceAtLeast(1),
            characterCount = totalChars,
            tags = tags.trim(),
            categoryId = categoryId,
            categoryLabel = categoryLabel.trim(),
            createdAt = now,
            updatedAt = now
        )
        val chunkEntities = draftChunks.map { draft ->
            SourceChunkEntity(
                sourceId = 0,
                chunkIndex = draft.chunkIndex,
                pageNumber = draft.pageNumber,
                sectionHeading = draft.sectionHeading,
                content = draft.content,
                normalizedTokens = draft.normalizedTokens
            )
        }
        val id = sourceDao.insertSourceWithChunks(doc, chunkEntities)
        recordGamificationActivity(xpEarned = 15, goalPointsEarned = 4)
        id
    }

    suspend fun updateSourceMeta(
        id: Long,
        title: String,
        tags: String,
        categoryId: Long? = null,
        categoryLabel: String = ""
    ) = withContext(Dispatchers.IO) {
        sourceDao.updateSourceMeta(id, title.trim(), tags.trim(), categoryId, categoryLabel.trim(), System.currentTimeMillis())
    }

    suspend fun deleteSource(source: SourceDocumentEntity) = withContext(Dispatchers.IO) {
        sourceDao.deleteSource(source)
    }

    suspend fun savePreparationItem(item: PreparationItemEntity): Long = withContext(Dispatchers.IO) {
        if (item.id == 0L) {
            preparationDao.insert(item)
        } else {
            preparationDao.update(item)
            item.id
        }
    }

    suspend fun deletePreparationItem(item: PreparationItemEntity) = withContext(Dispatchers.IO) {
        preparationDao.delete(item)
    }

    suspend fun saveOperator(item: OperatorItemEntity): Long = withContext(Dispatchers.IO) {
        if (item.id == 0L) {
            operatorDao.insert(item)
        } else {
            operatorDao.update(item)
            item.id
        }
    }

    suspend fun deleteOperator(item: OperatorItemEntity) = withContext(Dispatchers.IO) {
        operatorDao.delete(item)
    }

    suspend fun exportPreparationListJson(): String = withContext(Dispatchers.IO) {
        val items = preparationDao.getAll().map {
            PreparationExportItem(
                title = it.title,
                examDayTag = it.examDayTag,
                competenceFocusCodes = it.competenceFocusCodes,
                categoryOrCompetenceArea = it.categoryOrCompetenceArea,
                keywordsCsv = it.keywordsCsv,
                detailDepth = it.detailDepth.name,
                priority = it.priority.name,
                focusParametersCsv = it.focusParametersCsv,
                targetFlashcardCount = it.targetFlashcardCount,
                flashcardTypesCsv = it.flashcardTypesCsv,
                customPromptNote = it.customPromptNote
            )
        }
        json.encodeToString(items)
    }

    suspend fun importPreparationListJson(rawJson: String): Int = withContext(Dispatchers.IO) {
        val parsed = json.decodeFromString<List<PreparationExportItem>>(rawJson)
        val categories = categoryDao.getAll()
        var count = 0
        for (dto in parsed) {
            if (dto.title.isBlank()) continue
            val depth = runCatching { DetailDepth.valueOf(dto.detailDepth) }.getOrDefault(DetailDepth.STANDARD)
            val prio = runCatching { PriorityLevel.valueOf(dto.priority) }.getOrDefault(PriorityLevel.MEDIUM)
            val matchedCat = categories.firstOrNull {
                it.name.equals(dto.categoryOrCompetenceArea.trim(), ignoreCase = true) ||
                    it.shortCode.equals(dto.categoryOrCompetenceArea.trim(), ignoreCase = true) ||
                    (dto.examDayTag.isNotBlank() && it.shortCode.equals(dto.examDayTag.trim(), ignoreCase = true))
            }
            preparationDao.insert(
                PreparationItemEntity(
                    title = dto.title.trim(),
                    categoryOrCompetenceArea = matchedCat?.name ?: dto.categoryOrCompetenceArea.trim(),
                    categoryId = matchedCat?.id,
                    examDayTag = dto.examDayTag.trim(),
                    competenceFocusCodes = dto.competenceFocusCodes.trim(),
                    keywordsCsv = dto.keywordsCsv.trim(),
                    detailDepth = depth,
                    priority = prio,
                    focusParametersCsv = dto.focusParametersCsv.trim(),
                    targetFlashcardCount = dto.targetFlashcardCount.coerceIn(1, 50),
                    flashcardTypesCsv = dto.flashcardTypesCsv.ifBlank { "DEFINITION,CAUSAL,LIST,CLOZE,CAVE" },
                    customPromptNote = dto.customPromptNote.trim()
                )
            )
            count++
        }
        count
    }

    suspend fun exportOperatorsJson(): String = withContext(Dispatchers.IO) {
        val list = operatorDao.getAll().map {
            OperatorExportItem(
                name = it.name,
                requirementLevel = it.requirementLevel,
                description = it.description,
                exampleQuestion = it.exampleQuestion,
                expectationHorizon = it.expectationHorizon,
                commentNotes = it.commentNotes,
                gradingRubric = it.gradingRubric,
                caseRelationRule = it.caseRelationRule.name,
                questionPattern = it.questionPattern,
                structureType = it.structureType.name,
                defaultPoints = it.defaultPoints
            )
        }
        json.encodeToString(list)
    }

    suspend fun importOperatorsJson(rawJson: String): Int = withContext(Dispatchers.IO) {
        val parsed = json.decodeFromString<List<OperatorExportItem>>(rawJson)
        var count = 0
        for (dto in parsed) {
            if (dto.name.isBlank()) continue
            val st = runCatching { OperatorStructureType.valueOf(dto.structureType) }
                .getOrDefault(OperatorStructureType.CAUSAL_REASONING)
            val rule = runCatching { CaseRelationRule.valueOf(dto.caseRelationRule) }
                .getOrDefault(CaseRelationRule.OPTIONAL_CASE_RELATION)
            operatorDao.insert(
                OperatorItemEntity(
                    name = dto.name.trim(),
                    requirementLevel = dto.requirementLevel.trim(),
                    description = dto.description.trim(),
                    exampleQuestion = dto.exampleQuestion.trim(),
                    expectationHorizon = dto.expectationHorizon.trim(),
                    commentNotes = dto.commentNotes.trim(),
                    gradingRubric = dto.gradingRubric.trim(),
                    caseRelationRule = rule,
                    questionPattern = dto.questionPattern.trim(),
                    structureType = st,
                    defaultPoints = dto.defaultPoints.coerceIn(1, 30)
                )
            )
            count++
        }
        count
    }

    suspend fun generateFromPreparationItem(
        item: PreparationItemEntity,
        replacePreviousCardsForTopic: Boolean = false
    ): GenerationReport = withContext(Dispatchers.Default) {
        val config = item.toGenerationConfig()
        val chunkInputs = loadChunksForTopic(config.selectedSourceIds)

        when (
            val outcome: RetrievalOutcome = (SourceRetriever.retrieveAssigned(
                allChunks = chunkInputs,
                titleQuery = config.title,
                keywords = config.keywords,
                focusParameters = config.focusParameters,
                customPromptNote = config.customPromptNote,
                selectedSourceIds = config.selectedSourceIds,
                detailDepth = config.detailDepth
            ) as? RetrievalOutcome.Found) ?: RetrievalOutcome.Found(emptyList())
        ) {
            is RetrievalOutcome.NoSources -> GenerationReport.NoEvidenceFound(
                "Es sind aktuell keine eigenen Quellen hinterlegt (oder die ausgewählten Quellen existieren nicht mehr). Bitte fügen Sie zuerst eigene Quellen hinzu."
            )
            is RetrievalOutcome.NoMatches -> GenerationReport.NoEvidenceFound(
                "In ${outcome.searchedSourceCount} geprüften Quelle(n) wurden keine ausreichenden Belegstellen für „${item.title}“ " +
                    "(Suchbegriffe: ${outcome.searchedQueryTerms.joinToString(", ").ifBlank { "–" }}) gefunden. " +
                    "Die App erfindet keine Fakten – bitte prüfen Sie Ihre Keywords oder ergänzen Sie eine passende Quelle."
            )
            is RetrievalOutcome.Found -> {
                val activeAi = aiPreferencesStore.current()
                var usedLabel = "Lokale Offline-Engine"
                var fallbackHint: String? = null

                var note: de.pflegeexamen.app.domain.engine.GeneratedStudyNote? = null
                var cards: List<de.pflegeexamen.app.domain.engine.GeneratedFlashcard> = emptyList()

                if (activeAi.isOnDevice() || (activeAi.isReadyForRemoteCall() && outcome.passages.isNotEmpty())) {
                    val prompt = if (activeAi.isOnDevice()) AiPromptBuilder.buildOnDeviceStudyPrompt(config, outcome.passages) else AiPromptBuilder.buildStudyAndFlashcardPrompt(config, outcome.passages)
                    when (val res = if (activeAi.isOnDevice()) onDeviceAiClient.complete(prompt) else aiClient.complete(activeAi, prompt, expectJson = true)) {
                        is AiCallResult.Success -> {
                            val parsed = AiJsonResponseParser.parseStudyAndCards(res.text, config, if (activeAi.isOnDevice()) emptyList() else outcome.passages)
                            if (parsed != null) {
                                note = parsed.studyNote
                                cards = parsed.flashcards
                                usedLabel = res.providerLabel
                            } else {
                                fallbackHint = "Hinweis: KI-Antwort war unvollständig."
                            }
                        }
                        is AiCallResult.Failure -> {
                            fallbackHint = "Hinweis: ${res.reason}"
                        }
                    }
                }

                if (note == null && cards.isEmpty() && outcome.passages.isEmpty()) {
                    return@withContext GenerationReport.NoEvidenceFound(fallbackHint ?: "Keine Smartphone-KI verfügbar und keine passenden Inhalte im Lernpaket. Bitte KI aktivieren oder ein Lernpaket importieren.")
                }
                if (note == null && cards.isEmpty()) {
                    note = StudyNoteGenerator.generate(config, outcome.passages)
                    cards = FlashcardGenerator.generate(config, outcome.passages)
                }

                if (note == null && cards.isEmpty()) {
                    return@withContext GenerationReport.NoEvidenceFound(
                        "Es wurden Textstellen gefunden, jedoch konnten daraus mit den gewählten Parametern keine belastbaren Lernzettel oder Karteikarten extrahiert werden."
                    )
                }

                val now = System.currentTimeMillis()
                val catLabel = item.categoryOrCompetenceArea.trim()
                val noteId = note?.let { n ->
                    studyNoteDao.insert(
                        StudyNoteEntity(
                            prepItemId = item.id,
                            categoryId = item.categoryId,
                            categoryLabel = catLabel,
                            title = n.title,
                            summaryMarkdown = n.summaryMarkdown,
                            sectionsJson = json.encodeToString(n.sections),
                            sourceSummary = n.sourceSummary,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                if (replacePreviousCardsForTopic) {
                    flashcardDao.deleteByPrepItem(item.id)
                }

                if (cards.isNotEmpty()) {
                    val entities = cards.map { c ->
                        FlashcardEntity(
                            prepItemId = item.id,
                            studyNoteId = noteId,
                            categoryId = item.categoryId,
                            categoryLabel = catLabel,
                            topicTitle = item.title,
                            cardType = c.cardType,
                            frontText = c.frontText,
                            backText = c.backText,
                            citationLabel = c.citationLabel,
                            sourceExcerpt = c.sourceExcerpt,
                            parameterTag = c.parameterTag,
                            nextReviewAt = now,
                            createdAt = now
                        )
                    }
                    flashcardDao.insertAll(entities)
                }

                recordGamificationActivity(xpEarned = 10, goalPointsEarned = 5)

                val countHint = if (cards.size < item.targetFlashcardCount) {
                    "Aus den Quellen ließen sich ${cards.size} von ${item.targetFlashcardCount} gewünschten Karten belegbar ableiten."
                } else null

                val combinedHint = listOfNotNull(fallbackHint, countHint).joinToString("\n").ifBlank { null }

                GenerationReport.Success(
                    studyNoteId = noteId,
                    studyNoteTitle = note?.title ?: item.title,
                    flashcardCount = cards.size,
                    matchedPassageCount = outcome.passages.size,
                    sourceSummary = note?.sourceSummary.orEmpty(),
                    engineUsedLabel = usedLabel,
                    infoHint = combinedHint
                )
            }
        }
    }

    suspend fun generateCaseStudy(
        topicTitle: String,
        keywords: List<String>,
        prepItemId: Long?,
        categoryId: Long?,
        categoryLabel: String,
        selectedSourceIds: Set<Long>,
        selectedOperators: List<OperatorItemEntity>,
        detailDepth: DetailDepth = DetailDepth.EXAM_DEEP
    ): CaseGenerationReport = withContext(Dispatchers.Default) {
        if (selectedOperators.isEmpty()) {
            return@withContext CaseGenerationReport.NoEvidenceFound(
                "Bitte wählen Sie mindestens einen Operator aus Ihrer Operatorenliste aus."
            )
        }
        val topicSourceIds = prepItemId?.let { preparationDao.getById(it)?.toGenerationConfig()?.selectedSourceIds }.orEmpty()
        val chunkInputs = loadChunksForTopic(topicSourceIds)
        when (
            val outcome: RetrievalOutcome = (SourceRetriever.retrieveAssigned(
                allChunks = chunkInputs,
                titleQuery = topicTitle,
                keywords = keywords,
                focusParameters = listOf("Anatomie & Physiologie", "Definition", "Symptome", "Ursachen", "Pflegemaßnahmen", "Begründungen", "Cave"),
                selectedSourceIds = topicSourceIds.ifEmpty { selectedSourceIds },
                detailDepth = detailDepth
            ) as? RetrievalOutcome.Found) ?: RetrievalOutcome.Found(emptyList())
        ) {
            is RetrievalOutcome.NoSources -> CaseGenerationReport.NoEvidenceFound(
                "Es liegen noch keine eigenen Quellen vor. Bitte fügen Sie zuerst Skripte, PDFs, Word-Dateien, Texte oder Foto-Scans hinzu."
            )
            is RetrievalOutcome.NoMatches -> CaseGenerationReport.NoEvidenceFound(
                "Für „$topicTitle“ fanden sich in ${outcome.searchedSourceCount} Quelle(n) keine passenden Textstellen. " +
                    "Ohne Quellenbeleg wird kein Fallbeispiel erzeugt."
            )
            is RetrievalOutcome.Found -> {
                val specs = selectedOperators.map { op ->
                    OperatorPromptSpec(
                        operatorId = op.id,
                        name = op.name,
                        requirementLevel = op.requirementLevel,
                        description = op.description,
                        questionPattern = op.questionPattern,
                        structureType = op.structureType,
                        defaultPoints = op.defaultPoints,
                        exampleQuestion = op.exampleQuestion,
                        expectationHorizon = op.expectationHorizon,
                        commentNotes = op.commentNotes,
                        gradingRubric = op.gradingRubric,
                        caseRelationRule = op.caseRelationRule
                    )
                }

                val activeAi = aiPreferencesStore.current()
                var usedLabel = "Lokale Offline-Engine"
                var fallbackNote: String? = null
                var generated: de.pflegeexamen.app.domain.engine.GeneratedCaseStudy? = null

                if (activeAi.isOnDevice() || (activeAi.isReadyForRemoteCall() && outcome.passages.isNotEmpty())) {
                    val prompt = if (activeAi.isOnDevice()) AiPromptBuilder.buildOnDeviceCasePrompt(topicTitle, specs, outcome.passages) else AiPromptBuilder.buildCaseStudyPrompt(topicTitle, outcome.passages, specs)
                    when (val res = if (activeAi.isOnDevice()) onDeviceAiClient.complete(prompt) else aiClient.complete(activeAi, prompt, expectJson = true)) {
                        is AiCallResult.Success -> {
                            val parsed = AiJsonResponseParser.parseCaseStudy(res.text, topicTitle, if (activeAi.isOnDevice()) emptyList() else outcome.passages, specs)
                            if (parsed != null) {
                                generated = parsed
                                usedLabel = res.providerLabel
                            } else {
                                fallbackNote = "KI-Antwort war nicht strukturiert genug."
                            }
                        }
                        is AiCallResult.Failure -> {
                            fallbackNote = "${res.reason}"
                        }
                    }
                }

                if (generated == null && outcome.passages.isEmpty()) {
                    return@withContext CaseGenerationReport.NoEvidenceFound(fallbackNote ?: "Keine Smartphone-KI verfügbar und keine passenden Inhalte im Lernpaket. Bitte KI aktivieren oder ein Lernpaket importieren.")
                }
                if (generated == null) {
                    generated = CaseStudyGenerator.generate(
                        topicTitle = topicTitle,
                        passages = outcome.passages,
                        selectedOperators = specs
                    )
                }

                val finalCase = generated ?: return@withContext CaseGenerationReport.NoEvidenceFound(
                    "Aus den gefundenen Quellenstellen ließen sich leider keine eindeutigen Prüfungsfragen zu den gewählten Operatoren ableiten."
                )

                val caseEntity = CaseStudyEntity(
                    title = finalCase.title,
                    prepItemId = prepItemId,
                    categoryId = categoryId,
                    categoryLabel = categoryLabel.trim(),
                    vignetteText = finalCase.vignetteText,
                    isExtractedOriginalCase = finalCase.isExtractedOriginalCase,
                    vignetteCitationsJson = json.encodeToString(finalCase.vignetteCitations),
                    sourceBasisLabel = finalCase.sourceBasisLabel
                )
                val questionEntities = finalCase.questions.map { q ->
                    CaseQuestionEntity(
                        caseStudyId = 0,
                        orderIndex = q.orderIndex,
                        operatorId = q.operatorId,
                        operatorName = q.operatorName,
                        operatorLevel = q.operatorLevel,
                        operatorHint = q.operatorHint,
                        operatorGradingRubric = q.operatorGradingRubric,
                        operatorCommentNotes = q.operatorCommentNotes,
                        operatorCaseRelationRule = q.operatorCaseRelationRule,
                        questionText = q.questionText,
                        modelAnswerText = q.modelAnswerText,
                        expectedKeyPointsJson = json.encodeToString(q.expectedKeyPoints),
                        citationsJson = json.encodeToString(q.citations),
                        maxPoints = q.maxPoints,
                        totalKeyPointsCount = q.expectedKeyPoints.size
                    )
                }
                val caseId = caseStudyDao.insertCaseStudyWithQuestions(caseEntity, questionEntities)
                CaseGenerationReport.Success(
                    caseStudyId = caseId,
                    questionCount = questionEntities.size,
                    engineUsedLabel = usedLabel,
                    skippedOperatorsInfo = finalCase.skippedOperatorsInfo,
                    fallbackNote = fallbackNote
                )
            }
        }
    }

    suspend fun saveDraftCaseAnswer(questionId: Long, draftText: String) = withContext(Dispatchers.IO) {
        caseStudyDao.updateDraftAnswer(questionId, draftText)
    }

    suspend fun confirmAndEvaluateCaseAnswer(
        questionId: Long,
        finalAnswerText: String,
        answeredOnPaper: Boolean
    ) = withContext(Dispatchers.Default) {
        val current = caseStudyDao.getQuestionById(questionId) ?: return@withContext
        val expectedPoints: List<String> = runCatching {
            json.decodeFromString<List<String>>(current.expectedKeyPointsJson)
        }.getOrDefault(emptyList())

        val effectiveAnswer = if (answeredOnPaper && finalAnswerText.isBlank()) {
            "[Schriftlich auf Papier / außerhalb der App bearbeitet und bestätigt]"
        } else {
            finalAnswerText.trim()
        }

        val eval = if (answeredOnPaper && finalAnswerText.isBlank()) {
            null
        } else {
            AnswerEvaluator.evaluate(effectiveAnswer, expectedPoints)
        }

        var aiFeedback = ""
        val activeAi = aiPreferencesStore.current()
        if ((activeAi.isOnDevice() || activeAi.isReadyForRemoteCall()) && !answeredOnPaper && effectiveAnswer.isNotBlank()) {
            val prompt = AiPromptBuilder.buildAnswerFeedbackPrompt(
                questionText = current.questionText,
                operatorName = current.operatorName,
                modelAnswerText = current.modelAnswerText,
                userAnswerText = effectiveAnswer,
                gradingRubric = current.operatorGradingRubric,
                commentNotes = current.operatorCommentNotes
            )
            val res = if (activeAi.isOnDevice()) onDeviceAiClient.complete(AiPromptBuilder.buildOnDeviceFeedbackPrompt(current.questionText, current.operatorName, current.modelAnswerText, effectiveAnswer)) else aiClient.complete(activeAi, prompt, expectJson = false)
            if (res is AiCallResult.Success) {
                aiFeedback = res.text.trim()
            }
        }

        caseStudyDao.updateQuestion(
            current.copy(
                userAnswerText = effectiveAnswer,
                isAnswerConfirmed = true,
                confirmedAt = System.currentTimeMillis(),
                matchedKeyPointsCount = eval?.matchedPoints?.size ?: 0,
                totalKeyPointsCount = expectedPoints.size,
                matchedKeyPointsJson = json.encodeToString(eval?.matchedPoints ?: emptyList()),
                missingKeyPointsJson = json.encodeToString(eval?.missingPoints ?: emptyList()),
                aiFeedbackText = aiFeedback
            )
        )

        val bonusXp = if ((eval?.coveragePercent ?: 0) >= 70) 15 else 0
        recordGamificationActivity(xpEarned = 20 + bonusXp, goalPointsEarned = 10)
    }

    suspend fun updateCaseQuestionSelfEvaluation(questionId: Long, evaluation: SelfEvaluation) =
        withContext(Dispatchers.IO) {
            caseStudyDao.updateSelfEvaluation(questionId, evaluation)
        }

    suspend fun deleteCaseStudy(caseStudy: CaseStudyEntity) = withContext(Dispatchers.IO) {
        caseStudyDao.deleteCaseStudy(caseStudy)
    }

    suspend fun updateStudyNoteAnnotation(noteId: Long, annotation: String) = withContext(Dispatchers.IO) {
        studyNoteDao.updateAnnotation(noteId, annotation, System.currentTimeMillis())
    }

    suspend fun updateStudyNoteStatus(noteId: Long, previousStatus: MasteryStatus, newStatus: MasteryStatus) =
        withContext(Dispatchers.IO) {
            studyNoteDao.updateMasteryStatus(noteId, newStatus, System.currentTimeMillis())
            if (previousStatus != MasteryStatus.MASTERED && newStatus == MasteryStatus.MASTERED) {
                recordGamificationActivity(xpEarned = 25, goalPointsEarned = 10)
            }
        }

    suspend fun deleteStudyNote(note: StudyNoteEntity) = withContext(Dispatchers.IO) {
        studyNoteDao.delete(note)
    }

    suspend fun reviewFlashcard(card: FlashcardEntity, quality: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val next = SpacedRepetition.calculateNext(
            previousRepetitions = card.repetitions,
            previousIntervalDays = card.intervalDays,
            previousEaseFactor = card.easeFactor,
            quality = quality,
            nowMillis = now
        )
        flashcardDao.update(
            card.copy(
                repetitions = next.repetitions,
                intervalDays = next.intervalDays,
                easeFactor = next.easeFactor,
                nextReviewAt = next.nextReviewAt,
                lastReviewedAt = now
            )
        )
        val bonus = if (quality >= 4) 5 else 0
        recordGamificationActivity(xpEarned = 5 + bonus, goalPointsEarned = 2)
    }

    suspend fun deleteFlashcard(card: FlashcardEntity) = withContext(Dispatchers.IO) {
        flashcardDao.delete(card)
    }

    fun decodeCitations(jsonText: String): List<SourceCitation> =
        runCatching { json.decodeFromString<List<SourceCitation>>(jsonText) }.getOrDefault(emptyList())

    fun decodeStringList(jsonText: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(jsonText) }.getOrDefault(emptyList())

    private suspend fun recordGamificationActivity(xpEarned: Int, goalPointsEarned: Int) {
        val current = profileDao.getProfile() ?: StudyProfileEntity()
        val updated = GamificationEngine.applyActivity(
            current = StudyProfileState(
                totalXp = current.totalXp,
                streakDays = current.streakDays,
                freeDaysRemaining = current.freeDaysRemaining,
                dailyGoalPoints = current.dailyGoalPoints,
                todayPoints = current.todayPoints,
                lastActiveDayEpoch = current.lastActiveDayEpoch
            ),
            xpEarned = xpEarned,
            goalPointsEarned = goalPointsEarned
        )
        profileDao.upsert(
            current.copy(
                totalXp = updated.totalXp,
                streakDays = updated.streakDays,
                freeDaysRemaining = updated.freeDaysRemaining,
                todayPoints = updated.todayPoints,
                lastActiveDayEpoch = updated.lastActiveDayEpoch
            )
        )
    }

    private suspend fun loadChunksForTopic(assignedIds: Set<Long>): List<ChunkInput> {
        val chunks = loadChunkInputs()
        if (assignedIds.isNotEmpty()) return chunks.filter { it.sourceId in assignedIds }
        // Alt-Pakete ohne explizite Zuordnung bleiben durchsuchbar, aber Quellen,
        // die anderen Themen zugeordnet wurden, werden nicht versehentlich verwendet.
        val assignedElsewhere = preparationDao.getAll().flatMap {
            it.selectedSourceIdsCsv.split(",").mapNotNull { value -> value.trim().toLongOrNull() }
        }.toSet()
        return chunks.filterNot { it.sourceId in assignedElsewhere }
    }

    private suspend fun loadChunkInputs(): List<ChunkInput> {
        return sourceDao.getAllChunksWithSource().map { row ->
            ChunkInput(
                chunkId = row.chunk.id,
                sourceId = row.source.id,
                sourceTitle = row.source.title,
                sourceType = row.source.sourceType,
                pageNumber = row.chunk.pageNumber,
                sectionHeading = row.chunk.sectionHeading,
                content = row.chunk.content
            )
        }
    }

    private fun PreparationItemEntity.toGenerationConfig(): PrepGenerationConfig {
        val kw = keywordsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val params = focusParametersCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val types = flashcardTypesCsv.split(",")
            .mapNotNull { token -> runCatching { FlashcardType.valueOf(token.trim()) }.getOrNull() }
            .toSet()
        val sourceIds = selectedSourceIdsCsv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

        return PrepGenerationConfig(
            prepItemId = id,
            title = title,
            categoryOrCompetenceArea = categoryOrCompetenceArea,
            keywords = kw,
            detailDepth = detailDepth,
            focusParameters = params,
            targetFlashcardCount = targetFlashcardCount,
            flashcardTypes = types,
            selectedSourceIds = sourceIds,
            customPromptNote = customPromptNote
        )
    }

    companion object {
        const val CURRENT_CATALOG_SEED_VERSION = 4
    }
}
