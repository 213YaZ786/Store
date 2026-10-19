package com.yaz.store.feature.home

// "Update available" at launch, for Store's own newer release. Sources:
// - the update kit's UpdatePrompt (our code, GPL-3.0, https://github.com/213YaZ786/Calculator, app/src/main/java/com/yaz/calculator/ui/component/UpdatePrompt.kt;
//   template Modules shared/update/main/ui/component/UpdatePrompt.kt.tpl): a ZoneAlertDialog shown when a newer release exists, the new
//   version in its text, the install button and "Later" that hides it, nothing shown while the download runs but its progress;
// - the progress and its step words are the Home rows' own (HomeCards.Progress, jobText); the Update button is the red glass UpdateButton;
// - Google Play and the App Store never list the store among the apps to update: this dialog is the only place Store offers itself.
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yaz.store.R
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneAlertDialog

/**
 * Whether the dialog is on screen: Store's own release is newer, and "Later" was not chosen (a download in progress keeps it, so it
 * cannot vanish under the person's eyes).
 */
internal fun ownDialogShown(offer: OwnOffer?, later: Boolean, job: InstallJob?): Boolean =
    offer != null && (!later || job?.step?.active == true)

// The host reads the hoisted state so a test draws it with fixed data (https://developer.android.com/develop/ui/compose/state-hoisting),
// and shows the dialog as UpdatePrompt does, when `release` is set (see the sources above).
/** The dialog of [state] when it is due, with the size of the release from the state's details; [job] is Store's own queue entry. */
@Composable
internal fun OwnUpdateHost(state: HomeState, job: InstallJob?, onUpdate: () -> Unit, onLater: () -> Unit, onCancel: (String) -> Unit) {
    val offer = state.own
    if (offer == null || !ownDialogShown(offer, state.ownLater, job)) return
    val size = offer.entry.apkName?.let { state.details[offer.entry.detailsKey]?.sizes?.get(it) }
    OwnUpdateDialog(offer, job, size, onUpdate, { onCancel(offer.entry.packageName) }, onLater)
}

/** The dialog: "Store 0.1.0 → 0.1.1 · size", Update and Later; the step and a bar while Store's release downloads, with Cancel. */
@Composable
internal fun OwnUpdateDialog(offer: OwnOffer, job: InstallJob?, size: Long?, onUpdate: () -> Unit, onCancel: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val running = job?.step?.active == true
    val moves = if (size != null) {
        stringResource(R.string.own_versions_size, offer.from, offer.entry.versionName, Formatter.formatShortFileSize(context, size))
    } else {
        stringResource(R.string.own_versions, offer.from, offer.entry.versionName)
    }
    ZoneAlertDialog(
        onDismissRequest = { if (!running) onLater() },
        title = { Text(stringResource(R.string.own_title)) },
        text = {
            Column {
                Text(moves)
                if (job != null && (running || job.step == JobStep.FAILED)) Progress(job)
            }
        },
        confirmButton = {
            if (!running) UpdateButton(onClick = onUpdate, label = stringResource(R.string.action_update))
        },
        dismissButton = {
            if (running) QuietButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            else QuietButton(onClick = onLater) { Text(stringResource(R.string.own_later)) }
        }
    )
}
