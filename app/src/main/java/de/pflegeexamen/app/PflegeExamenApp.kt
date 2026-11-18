package de.pflegeexamen.app

import android.app.Application
import de.pflegeexamen.app.data.content.ContentSyncScheduler
import de.pflegeexamen.app.data.content.PublishedContentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PflegeExamenApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var contentStore: PublishedContentStore
        private set

    override fun onCreate() {
        super.onCreate()
        contentStore = PublishedContentStore(applicationContext)
        ContentSyncScheduler.schedule(applicationContext)
        appScope.launch { contentStore.refresh() }
    }
}
