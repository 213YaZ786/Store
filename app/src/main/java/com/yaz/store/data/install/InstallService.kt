package com.yaz.store.data.install

// Ported from Files (YAZ/Files at HEAD, data/files/FileJobService.kt, FileJobReceiver.kt and FileJobPlan.kt), itself Material Files
// (GPL-3.0-or-later, https://github.com/zhanghai/MaterialFiles): filejob/FileJobService.kt (a foreground service while jobs run),
// filejob/FileJobNotificationTemplate.kt (a channel of low importance with no badge; an ongoing, progress, only-alert-once notification),
// filejob/FileJobReceiver.kt (the Cancel action is a broadcast to a receiver) and util/ForegroundNotificationManager.kt (startForeground
// first, notify for the updates, stopForeground REMOVE at the end).
// Changes: the queue lives in InstallQueue; the service reads its list and shows the entry that is running, with a Cancel for it and, when
// several wait, a Cancel all. It has the type dataSync (https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)
// and stops itself when no entry is active; Android 15 limits dataSync to six hours in 24, the system calls onTimeout(int, int) and the
// service stops within seconds (https://developer.android.com/develop/background-work/services/fgs/timeout), so the queue is cancelled.
// Not sticky: the queue lives in this process, a restart would have none.
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yaz.store.MainActivity
import com.yaz.store.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** What to do with the foreground service (FileJobPlan, Files at HEAD): start it for the first active entry, keep it, stop it when none is left. */
enum class ServiceStep { START, KEEP, STOP }

object InstallServicePlan {
    /** [active]: entries queued or running. [serviceUp]: the service is running and not stopping. */
    fun next(active: Int, serviceUp: Boolean): ServiceStep = when {
        active > 0 && !serviceUp -> ServiceStep.START
        active <= 0 && serviceUp -> ServiceStep.STOP
        else -> ServiceStep.KEEP
    }
}

/** What the notification shows: the app's name, a line for its step, a bar while it downloads. */
class NoticeText(val title: String, val text: String, val percent: Int?)

object InstallNotice {
    /** The entry the notification is about: the one that runs, else the first that waits; null when none is active. */
    fun current(jobs: List<InstallJob>): InstallJob? =
        jobs.firstOrNull { it.step.active && it.step != JobStep.QUEUED } ?: jobs.firstOrNull { it.step.active }

    fun describe(jobs: List<InstallJob>): NoticeText? {
        val job = current(jobs) ?: return null
        val others = jobs.count { it.step.active } - 1
        val step = when (job.step) {
            JobStep.QUEUED -> "Waiting"
            JobStep.DOWNLOADING -> "Downloading"
            JobStep.CHECKING -> "Checking"
            JobStep.INSTALLING -> "Installing"
            JobStep.CONFIRM -> "Waiting for your confirmation"
            else -> ""
        }
        val text = if (others > 0) "$step, $others more waiting" else step
        return NoticeText(job.entry.label, text, if (job.step == JobStep.DOWNLOADING) (job.progress * 100).toInt().coerceIn(0, 100) else null)
    }
}

class InstallService : Service(), KoinComponent {

    private val queue: InstallQueue by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStartId = 0
    private var watching = false

    override fun onCreate() {
        super.onCreate()
        up = true
        InstallNotices.createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        up = true
        // The first notification has to be up within seconds of startForegroundService, whatever the queue holds.
        try {
            ServiceCompat.startForeground(
                this, InstallNotices.ID, InstallNotices.build(this, queue.jobs.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: RuntimeException) {
            // Android refused the foreground (the app is not in a state that may start one): the queue goes on in the app.
            up = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                queue.jobs.collect { list ->
                    if (!up) return@collect
                    if (InstallServicePlan.next(list.count { it.step.active }, up) == ServiceStep.STOP) stop() else InstallNotices.post(this@InstallService, list)
                }
            }
        }
        if (queue.jobs.value.none { it.step.active }) stop()
        return START_NOT_STICKY
    }

    private fun stop() {
        up = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        InstallNotices.remove(this)
        stopSelf(lastStartId)
    }

    // The time limit of a dataSync service on Android 15 and later (the docs above): the queue is cancelled, the service stops.
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.cancelAll()
        stop()
    }

    override fun onDestroy() {
        up = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The service is running and not on its way out. */
        @Volatile private var up = false

        /** Called when an entry is queued: starts the service for the first one. A start Android refuses leaves the queue running in the app. */
        fun onQueued(context: Context) {
            if (InstallServicePlan.next(1, up) != ServiceStep.START) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, InstallService::class.java)) }
        }
    }
}

/** The channel and the notification of the queue. */
internal object InstallNotices {
    const val ID = 7401
    private const val CHANNEL = "install"

    // Material Files' channel (FileJobNotificationTemplate.kt): low importance, no badge, no sound.
    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Installs and updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows an app downloading and installing, and cancels it"
            setShowBadge(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, jobs: List<InstallJob>): Notification {
        val text = InstallNotice.describe(jobs) ?: NoticeText("Store", "Starting", null)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_store)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setSubText(text.percent?.let { "$it%" })
            .apply { if (text.percent != null) setProgress(100, text.percent, false) }
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val current = InstallNotice.current(jobs)
        builder.addAction(action(context, "Cancel", InstallCancelReceiver.cancelIntent(context, current?.entry?.packageName), 1))
        if (jobs.count { it.step.active } > 1) builder.addAction(action(context, "Cancel all", InstallCancelReceiver.cancelIntent(context, null), 2))
        return builder.build()
    }

    private fun action(context: Context, label: String, intent: Intent, code: Int) = NotificationCompat.Action.Builder(
        0, label, PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    ).build()

    fun post(context: Context, jobs: List<InstallJob>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(ID, build(context, jobs)) }
    }

    fun remove(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}

/**
 * The Cancel action of the notification (Files' FileJobReceiver at HEAD, itself Material Files' filejob/FileJobReceiver.kt): a broadcast
 * with the action "cancel" to this receiver; the intent names it explicitly, and it is declared android:exported="false".
 */
class InstallCancelReceiver : BroadcastReceiver(), KoinComponent {
    private val queue: InstallQueue by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val name = intent.getStringExtra(EXTRA_PACKAGE)
        if (name != null) queue.cancel(name) else queue.cancelAll()
    }

    companion object {
        private const val ACTION_CANCEL = "com.yaz.store.action.CANCEL_INSTALL"
        private const val EXTRA_PACKAGE = "package"

        /** Cancels the entry of [packageName]; every entry when null. */
        fun cancelIntent(context: Context, packageName: String?): Intent =
            Intent(context, InstallCancelReceiver::class.java).setAction(ACTION_CANCEL).apply { if (packageName != null) putExtra(EXTRA_PACKAGE, packageName) }
    }
}
