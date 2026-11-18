package de.pflegeexamen.app.data.content

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import de.pflegeexamen.app.PflegeExamenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class ContentSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters?): Boolean {
        val app = application as? PflegeExamenApp ?: return false
        scope.launch {
            try { app.contentStore.refresh() } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean { scope.coroutineContext.cancelChildren(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

object ContentSyncScheduler {
    private const val CONTENT_JOB_ID = 202702
    private const val LEGACY_RANKING_JOB_ID = 202701
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        scheduler.cancel(LEGACY_RANKING_JOB_ID) // Never submit pre-quiz XP to the new leaderboard.
        if (PublishedContentStore.CONTENT_URL.isBlank()) { scheduler.cancel(CONTENT_JOB_ID); return }
        if (scheduler.allPendingJobs.any { it.id == CONTENT_JOB_ID }) return
        scheduler.schedule(JobInfo.Builder(CONTENT_JOB_ID, ComponentName(context, ContentSyncJobService::class.java))
            .setPeriodic(60L * 60L * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build())
    }
}
