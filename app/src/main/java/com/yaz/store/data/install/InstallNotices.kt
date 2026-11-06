package com.yaz.store.data.install

// A confirmation Android asks, or a failure, while Store is not in front: a notification that opens the app, withdrawn when the app comes back.
// Ported from GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore):
// - core/PkgInstallerStatusReceiver.kt, onReceive: the PENDING_USER_ACTION branch (a pending action and a notification of the channel "confirmation
//   required": the title, the text for an update or an install) and the failure branch (a notification of the channel "installation failed", none when
//   the status is STATUS_FAILURE_ABORTED, which is the person's own No);
// - ui/ActivityUtils.kt, addPendingAction, cancelPendingAction and onActivityResumedOrPaused: the action runs at once when an activity is resumed,
//   else it waits 300 ms (an activity launched by us may be on its way back), then its notification is posted and the action stays until an
//   activity resumes. Resumed is tracked with onResume and onPause, not onStart and onStop, as the source explains.
// Changes: the receiver of the source is the queue's flow, read by InstallService while the queue runs (nothing new runs in the background); the
// action the source launches when the app is back is Android's confirmation screen, which ConfirmWhileInFront (feature/home/HomeScreen.kt) starts
// from the entry still in CONFIRM, so launching an action here is only withdrawing its notification.
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.ArraySet
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yaz.store.MainActivity
import com.yaz.store.R
import com.yaz.store.data.catalog.Installed

// not ported: the uninstall branch, the multi-package session branch and isPrivilegedInstaller's EXTRA_NOT_UNKNOWN_SOURCE (Store never uninstalls or
// installs a group of packages in one session, and is not a privileged installer)
// not ported: CH_AUTO_UPDATE_CONFIRMATION_REQUIRED and CH_AUTO_UPDATE_FAILED (the queue only runs what the person started)
// not ported: the STATUS_SUCCESS branch, its completion channel, showAllUpToDateNotification and the "updated packages" notifications (not taken, Store's note)
// not ported: sessionCompletionChannels and getIntentSender (the install kit answers the queue)
// not ported: EXTRA_LEGACY_STATUS and PackageInstallerError (the kit turns the status into words, see InstallQueue)
// not ported: DetailsScreen.createPendingIntent and ErrorDialog.createPendingDialog (a notification opens the app, where Details shows the failure)
// not ported: ActivityUtils.init and maybeAddPendingActionFromTrustedIntent (a process that died has no queue to ask for a confirmation)
// not ported: PendingAction.toBundle, fromTrustedBundle, fromTrustedIntent, PendingActivityIntent and PendingDialog (for the same reason, and the actions here carry nothing)
// not ported: checkMainThread (the service and the lifecycle callbacks both run on the main thread)
internal object ConfirmNotices {
    // The channels: a confirmation is asked to be seen at once (high), a failure to be seen (default).
    const val CH_CONFIRMATION_REQUIRED = "confirmation_required"
    const val CH_INSTALLATION_FAILED = "installation_failed"
    private const val FIRST_DYNAMIC_ID = 7500

    // The status words of a cancel by the person, the kit's InstallMessages (STATUS_FAILURE_ABORTED): no notification for it.
    private const val ABORTED = "The installation was cancelled."

    private lateinit var appContext: Context
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var prevDynamicNotificationId = FIRST_DYNAMIC_ID

    private val resumedActivities = ArrayList<Activity>()
    private val pendingActions = ArrayList<PendingAction>()
    private val delayedPendingActions = ArraySet<PendingAction>()

    // The serials of the entries already handled: a confirmation asked, a failure told, an entry seen working.
    private val confirming = HashSet<Long>()
    private val failureTold = HashSet<Long>()
    private val seenWorking = HashSet<Long>()

    /** Registers the resumed counter (StoreApplication): ActivityLifecycleCallbacks, as ActivityUtils' callers do from the activity. */
    fun register(app: Application) {
        appContext = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = onActivityResumedOrPaused(activity, true)
            override fun onActivityPaused(activity: Activity) = onActivityResumedOrPaused(activity, false)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The two channels, created beside InstallNotices.createChannel (Notifications.init of the source). */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_CONFIRMATION_REQUIRED, context.getString(R.string.channel_confirmation), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_INSTALLATION_FAILED, context.getString(R.string.channel_failed), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) }
        )
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/Notifications.kt#generateId
    private fun generateId(): Int {
        val res = prevDynamicNotificationId++
        check(res < Int.MAX_VALUE)
        return res
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/PkgInstallerStatusReceiver.kt#onReceive
    /** Each emission of the queue's flow: a confirmation asked or a failure is told, an entry that left CONFIRM withdraws its notification. */
    fun onReceive(context: Context, jobs: List<InstallJob>) {
        appContext = context.applicationContext
        for (job in jobs) {
            if (job.step.active) seenWorking.add(job.serial)
            if (job.step == JobStep.CONFIRM && confirming.add(job.serial)) {
                val pkg = job.entry.packageName
                addPendingAction(PendingAction(job.serial, failure = false)) {
                    // don't use the label of an older read, the entry carries the one of its release
                    val textId = if (Installed.read(appContext, pkg) != null) R.string.notice_confirm_update else R.string.notice_confirm_install
                    builder(CH_CONFIRMATION_REQUIRED).apply {
                        setContentTitle(appContext.getString(textId, job.entry.label))
                        setContentText(appContext.getString(R.string.notice_confirm_text))
                    }.build()
                }
            }
            // Only an entry seen working is told: the failed ones of an earlier run stay on their cards, and a cancel by the person is none.
            if (job.step == JobStep.FAILED && job.serial in seenWorking && failureTold.add(job.serial) && job.why != ABORTED) {
                addPendingAction(PendingAction(job.serial, failure = true)) {
                    builder(CH_INSTALLATION_FAILED).apply {
                        setContentTitle(appContext.getString(R.string.error_not_installed, job.entry.label))
                        setContentText(appContext.getString(R.string.notice_failed_text))
                    }.build()
                }
            }
        }
        // An entry that left CONFIRM (installing, done, failed, cancelled) has nothing left to ask.
        confirming.toList().forEach { serial ->
            if (jobs.none { it.serial == serial && it.step == JobStep.CONFIRM }) {
                confirming.remove(serial)
                cancelPendingAction(serial, failure = false)
            }
        }
        seenWorking.retainAll(jobs.mapTo(HashSet()) { it.serial })
        failureTold.retainAll(jobs.mapTo(HashSet()) { it.serial })
    }

    // The notification of the source's Notifications.builder: a small icon, the explicit and immutable intent that opens the app, shown with its time.
    private fun builder(channel: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            appContext, 0, Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_stat_store)
            .setContentIntent(open)
            .setShowWhen(true)
            // otherwise the notification of a pending action goes before the app is back
            .setAutoCancel(false)
            .setStyle(NotificationCompat.BigTextStyle())
    }

    /** What to do when the app is back: withdraw the notification (the source launches an intent or a dialog here, see the note above). */
    class PendingAction(val serial: Long, val failure: Boolean) {
        var notificationId: Int = 0

        fun launch() {
            execute()
            if (notificationId != 0) NotificationManagerCompat.from(appContext).cancel(notificationId)
        }

        // The source's execute starts an activity or navigates to a dialog; Store's action is done by ConfirmWhileInFront and the cards.
        private fun execute() = Unit
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/ActivityUtils.kt#addPendingAction
    fun addPendingAction(action: PendingAction, notification: () -> Notification) {
        mostRecentResumedActivity()?.let {
            action.launch()
            return
        }

        // Sometimes pending action is added right before the activity becomes visible again, eg when an OS activity launched by us launches our
        // PendingIntent and immediately finishes. In this case, notification would be shown and dismissed immediately, which is undesirable.
        // Delay adding of PendingAction for a bit as a workaround
        check(delayedPendingActions.add(action))
        mainHandler.postDelayed({
            if (!delayedPendingActions.remove(action)) {
                return@postDelayed
            }

            mostRecentResumedActivity()?.let {
                action.launch()
                return@postDelayed
            }

            if (action.notificationId != 0) {
                pendingActions.removeAll { it.notificationId == action.notificationId }
            }

            pendingActions.add(action)

            if (action.notificationId == 0) {
                action.notificationId = generateId()
            }
            val manager = NotificationManagerCompat.from(appContext)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(action.notificationId, notification()) }
        }, 300L)
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/ActivityUtils.kt#cancelPendingAction
    // By the entry's serial instead of the notification id: a delayed action has none yet.
    fun cancelPendingAction(serial: Long, failure: Boolean) {
        delayedPendingActions.removeIf { it.serial == serial && it.failure == failure }
        pendingActions.filter { it.serial == serial && it.failure == failure }.forEach { action ->
            pendingActions.remove(action)
            if (action.notificationId != 0) NotificationManagerCompat.from(appContext).cancel(action.notificationId)
        }
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/ActivityUtils.kt#onActivityResumedOrPaused
    // Use onResume/onPause instead of onStart/onStop, because the latter are not executed when non-full-screen activity is launched by us.
    fun onActivityResumedOrPaused(activity: Activity, resumed: Boolean) {
        if (resumed) {
            if (activity is MainActivity) {
                // the pending actions are launched now: their notifications go, the cards and ConfirmWhileInFront show what they were about
                pendingActions.toList().forEach { it.launch() }
                pendingActions.clear()
            }
            // The source does not add the activity when it launched an intent, to avoid a race; here nothing is launched, so it is always added.
            if (!resumedActivities.contains(activity)) resumedActivities.add(activity)
        } else {
            resumedActivities.remove(activity)
        }
    }

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/ActivityUtils.kt#mostRecentResumedActivity
    fun mostRecentResumedActivity(): Activity? = resumedActivities.lastOrNull()
}
