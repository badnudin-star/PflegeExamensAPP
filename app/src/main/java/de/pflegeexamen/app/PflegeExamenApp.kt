package de.pflegeexamen.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import de.pflegeexamen.app.data.ai.AiPreferencesStore
import de.pflegeexamen.app.data.ai.HttpAiTextClient
import de.pflegeexamen.app.data.ingestion.DocumentIngestionService
import de.pflegeexamen.app.data.ingestion.OcrProcessor
import de.pflegeexamen.app.data.local.AppDatabase
import de.pflegeexamen.app.data.ranking.HttpRankingClient
import de.pflegeexamen.app.data.ranking.RankingPreferencesStore
import de.pflegeexamen.app.data.ranking.RankingSyncScheduler
import de.pflegeexamen.app.data.repository.PflegeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PflegeExamenApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repository: PflegeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        val database = AppDatabase.getInstance(applicationContext)
        val ocrProcessor = OcrProcessor(applicationContext)
        val ingestionService = DocumentIngestionService(applicationContext, ocrProcessor)
        val aiPreferencesStore = AiPreferencesStore(applicationContext)
        val aiClient = HttpAiTextClient()
        val rankingPreferencesStore = RankingPreferencesStore(applicationContext)
        val rankingClient = HttpRankingClient()

        repository = PflegeRepository(
            db = database,
            ingestionService = ingestionService,
            aiPreferencesStore = aiPreferencesStore,
            aiClient = aiClient,
            rankingPreferencesStore = rankingPreferencesStore,
            rankingClient = rankingClient
        )

        RankingSyncScheduler.scheduleHourly(applicationContext)

        appScope.launch {
            repository.ensureInitialized()
            repository.syncRanking(forceEvenIfNotDue = false)
        }
    }
}
