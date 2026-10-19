package com.yaz.store.data.update

// The background check, ported from GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore):
// - autoupdate/AutoUpdatePrefs.kt: updateJobs / scheduleUpdateCheckJob (a periodic JobInfo built with JobScheduler, cancelled when the
//   setting is off, left alone when the same job is pending already) and its network constraint (JobInfo.NETWORK_TYPE_UNMETERED, one of the
//   choices of its job network type);
// - autoupdate/UpdateCheckJob.kt: a JobService that asks the repo for the latest packages, collects the outdated ones and shows an
//   "updates available" notification that opens the app (its title counts the updates, its text names them); it installs nothing.
// Changes: the setting is Store's own and off by default; one job a day; setPersisted(false), because a persisted job needs
// RECEIVE_BOOT_COMPLETED, which Store does not ask for (https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPersisted(boolean)):
// after a restart of the phone the job is scheduled again the first time Store opens (StoreApplication); the check is Catalog.load and
// Installed.statuses, the screens' own; only the notification is new.
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yaz.store.MainActivity
import com.yaz.store.R
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.Installed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Schedules and cancels the check (GrapheneOS' AutoUpdatePrefs.updateJobs). */
object UpdateJobs {
    const val JOB_ID = 7402
    private const val DAY = 24L * 60 * 60 * 1000

    /** Makes the scheduler match [enabled]: the job once a day on an unmetered network, or no job. */
    fun sync(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val pending = scheduler.getPendingJob(JOB_ID)
        if (!enabled) {
            if (pending != null) scheduler.cancel(JOB_ID)
            return
        }
        // The same job is left alone, so a start of the app does not push its next run back.
        if (pending != null && pending.isPeriodic && pending.intervalMillis == DAY && pending.networkType == JobInfo.NETWORK_TYPE_UNMETERED) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPeriodic(DAY)
            .setPersisted(false)
            .build()
        scheduler.schedule(info)
    }
}

/** What the notification says: the count and the names. Pure, so a test reads it. */
object UpdateNotice {
    fun waiting(statuses: List<AppStatus>): List<AppStatus> = statuses.filter { it.state == AppState.UPDATE }

    private const val CHANNEL = "updates"
    private const val ID = 7403

    // The channel of a notification that is meant to be seen: default importance, no badge (GrapheneOS' Notifications.kt has one channel per kind).
    private fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, waiting: List<AppStatus>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        channel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_store)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notice_updates, waiting.size, waiting.size))
            .setContentText(waiting.joinToString { it.entry.label })
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(ID, notification) }
    }
}

class UpdateCheckJob : JobService(), KoinComponent {
    private val catalog: Catalog by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    // GrapheneOS' UpdateCheckJob.onStartJob: the work runs in a coroutine, jobFinished ends it, onStopJob cancels it.
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val result = catalog.load()
            val waiting = UpdateNotice.waiting(Installed.statuses(applicationContext, result.entries))
            if (waiting.isNotEmpty()) UpdateNotice.show(applicationContext, waiting)
            // Nothing read at all: tried again later by the scheduler's own back-off.
            jobFinished(params, result.entries.isEmpty() && result.error != null)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        scope.cancel()
        // "true" means "reschedule the job" (GrapheneOS' UpdateCheckJob.onStopJob).
        return true
    }
}
