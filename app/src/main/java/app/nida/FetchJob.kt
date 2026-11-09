package app.nida

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * When the widget's cached days run low and the app hasn't been opened, fetch more for the
 * last known place once the phone has a connection. No GPS here.
 */
class FetchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Refresh.run {
            Places.here(this)?.let { Times.ensure(this, it) }
            NidaWidget.refreshAll(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 1
        private const val KEY_LAST = "fetch_job_at"
        private const val SPACING = 30 * 60_000L

        fun schedule(context: Context) {
            val prefs = Store.prefs(context)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST, 0) < SPACING) return
            prefs.edit().putLong(KEY_LAST, now).apply()
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ID, ComponentName(context, FetchJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
    }
}
