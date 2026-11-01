package com.yaz.store.feature.home

// The words of Home that depend on state, read from strings.xml (Android's string resources and plurals,
// https://developer.android.com/guide/topics/resources/string-resource): what an entry of the queue is doing, and why the list is
// incomplete. The time of GitHub's limit is shown in the phone's format, android.text.format.DateFormat
// (https://developer.android.com/reference/android/text/format/DateFormat#getTimeFormat(android.content.Context)). The sizes of a
// download come from android.text.format.Formatter.formatShortFileSize
// (https://developer.android.com/reference/android/text/format/Formatter#formatShortFileSize(android.content.Context,%20long)).
import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import com.yaz.store.R
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import java.util.Date

/** What an entry of the queue is doing, in words. */
fun jobText(context: Context, job: InstallJob): String = when (job.step) {
    JobStep.QUEUED -> context.getString(R.string.job_waiting)
    JobStep.DOWNLOADING ->
        if (job.bytesTotal > 0) {
            // As Google Play: "12 MB of 39 MB", the sizes in the phone's units.
            context.getString(
                R.string.job_downloading_size,
                Formatter.formatShortFileSize(context, job.bytesDone.coerceIn(0L, job.bytesTotal)),
                Formatter.formatShortFileSize(context, job.bytesTotal)
            )
        } else {
            context.getString(R.string.job_downloading, (job.progress * 100).toInt().coerceIn(0, 100))
        }
    JobStep.CHECKING -> context.getString(R.string.job_checking)
    JobStep.INSTALLING -> context.getString(R.string.job_installing)
    JobStep.CONFIRM -> context.getString(R.string.job_confirm)
    JobStep.DONE -> context.getString(R.string.job_done)
    JobStep.FAILED -> context.getString(R.string.job_failed, job.why ?: context.getString(R.string.job_failed_default))
}

/** The title of the banner or empty zone for [error]. */
fun errorTitle(context: Context, error: CatalogError): String = context.getString(
    when (error) {
        CatalogError.Offline -> R.string.error_offline
        is CatalogError.RateLimited -> R.string.error_limit
        CatalogError.Refused -> R.string.error_refused
    }
)

/** The line under it: when to come back, and that the repos come from the last visit. Null when there is nothing to add. */
fun errorDetail(context: Context, error: CatalogError, listCached: Boolean): String? {
    val parts = buildList {
        if (error is CatalogError.RateLimited) {
            add(
                error.resetAt?.let { context.getString(R.string.error_limit_at, DateFormat.getTimeFormat(context).format(Date(it * 1000))) }
                    ?: context.getString(R.string.error_limit_later)
            )
        }
        if (listCached) add(context.getString(R.string.error_kept))
    }
    return parts.joinToString(" ").takeIf { it.isNotEmpty() }
}
