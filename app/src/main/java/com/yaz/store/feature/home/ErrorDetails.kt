package com.yaz.store.feature.home

// The details of an install that failed: a dialog with the reason, the system's own text when there is one, a Copy and a Close.
// Ported from GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore), ui/ErrorDialog.kt:
// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/ErrorDialog.kt#ErrorDialog
// Changes: the dialog is the kit's ZoneAlertDialog, drawn from the failed entry of the queue instead of a DialogFragment with navigation arguments.
// Android 13 and later show their own confirmation of a copy (Android's clipboard guide, "Copy and paste"), so the short toast is for older Androids only.
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.yaz.store.R
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneAlertDialog

// not ported: onCreateDialog (its Dialog is built by MaterialAlertDialogBuilder, a View dialog; ErrorDialog below is the same title, message and two buttons in the kit's ZoneAlertDialog)
// not ported: createArgs (the navigation arguments of the source's nav graph; the failed entry of the queue is the dialog's data)
// not ported: createPendingDialog (the source's PendingDialog action; InstallNotices has its own pending action)
// not ported: show (fragment.findNavController().navigate; HomeCards opens the dialog from a remembered flag)
// not ported: createPendingIntent (NavDeepLinkBuilder to the error dialog; the notification of a failure opens the app, see InstallNotices)

// The source's ErrorTemplate: the title and the message of an error, made from a Context.
class ErrorTemplate(val title: (Context) -> String, val message: (Context) -> String)

@Composable
fun ErrorDialog(template: ErrorTemplate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val title = template.title(ctx)
    val message = template.message(ctx)
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        // setPositiveButton(R.string.dismiss, null)
        confirmButton = { BoldButton(onClick = onDismiss) { Text(ctx.getString(R.string.dismiss)) } },
        // setNeutralButton(R.string.btn_copy) { ClipData.newPlainText(title, message), setPrimaryClip(cd) }
        dismissButton = {
            QuietButton(onClick = {
                val cd = ClipData.newPlainText(title, message)
                ctx.getSystemService<ClipboardManager>()!!.setPrimaryClip(cd)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }) { Text(ctx.getString(R.string.btn_copy)) }
        }
    )
}
