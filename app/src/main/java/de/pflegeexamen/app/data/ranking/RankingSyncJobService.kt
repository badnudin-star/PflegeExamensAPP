package de.pflegeexamen.app.data.ranking

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

/**
 * Leichtgewichtiger nativer JobService (ohne Drittanbieter-Bibliothek),
 * der einmal pro Stunde bei Internetverbindung das globale Ranking aktualisiert.
 */
class RankingSyncJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = application as? PflegeExamenApp ?: return false
        jobScope.launch {
            runCatching {
                app.repository.syncRanking(forceEvenIfNotDue = false)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        jobScope.cancel()
        super.onDestroy()
    }
}

object RankingSyncScheduler {
    private const val JOB_ID = 202701
    private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L

    fun scheduleHourly(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val existing = scheduler.allPendingJobs.any { it.id == JOB_ID }
        if (existing) return

        val component = ComponentName(context, RankingSyncJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(ONE_HOUR_MILLIS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        scheduler.schedule(info)
    }
}
