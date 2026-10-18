package com.yaz.store.core.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// An install through PackageInstaller's public session API, written after AOSP's ApiDemos InstallApkSessionApi
// (createSession with SessionParams MODE_FULL_INSTALL, openWrite, a commit with an IntentSender, the status handled
// with STATUS_PENDING_USER_ACTION starting EXTRA_INTENT):
// https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java
// and App Manager's PackageInstallerCompat (GPL-3.0-or-later): setInstallReason(USER), setPackageSource, the session
// recorded in its registry, each split written with fsync, the session abandoned on any failure:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerCompat.java
// The commit's PendingIntent is explicit and mutable (the system fills in the status), as Android requires for a
// session result: https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender)
// The confirmation screen is started only when it resolves to a system app, against intent redirection:
// https://developer.android.com/privacy-and-security/risks/intent-redirection

/** The APKs to install: the base and the chosen splits, written to the session in this order. */
class InstallRequest(val packageName: String, val files: List<File>)

sealed interface InstallState {
    data object Idle : InstallState
    data class Writing(val written: Long, val total: Long) : InstallState
    /** Committed; Android is working, or showing its own screen. */
    data object Working : InstallState
    /** Android wants the person's yes in its screen; [intent] opens it. */
    data class Confirm(val intent: Intent) : InstallState
    data class Done(val packageName: String) : InstallState
    data class Failed(val failure: InstallFailure) : InstallState
}

class Installer(private val context: Context, private val registry: SessionRegistry) {

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    @Volatile private var sessionId = -1
    @Volatile private var packageName = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { InstallEvents.events.collect(::onEvent) }
    }

    /** Writes [request] into a new session and commits it. Cancelling the caller abandons the session. */
    suspend fun install(request: InstallRequest) {
        packageName = request.packageName
        val total = request.files.sumOf { it.length() }
        _state.value = InstallState.Writing(0, total)
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            var committed = false
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(request.packageName)
                    setSize(total)
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                    }
                }
                val id = try {
                    installer.createSession(params)
                } catch (e: Exception) {
                    fail(InstallMessages.SESSION_CREATE)
                    return@withContext
                }
                sessionId = id
                registry.record(id)
                installer.openSession(id).use { session ->
                    var written = 0L
                    var shown = 0L
                    for (file in request.files) {
                        file.inputStream().use { input ->
                            session.openWrite(file.name, 0, file.length()).use { out ->
                                val buffer = ByteArray(BUFFER)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    written += n
                                    if (written - shown >= SHOWN_STEP) {
                                        shown = written
                                        _state.value = InstallState.Writing(written, total)
                                    }
                                }
                                session.fsync(out)
                            }
                        }
                    }
                    _state.value = InstallState.Writing(total, total)
                    val result = PendingIntent.getBroadcast(
                        context,
                        id,
                        Intent(context, InstallResultReceiver::class.java)
                            .setAction(InstallResultReceiver.ACTION)
                            .setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    // Before the commit: Android may answer before commit returns.
                    _state.value = InstallState.Working
                    committed = true
                    session.commit(result.intentSender)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                fail(InstallMessages.SESSION_WRITE)
            } catch (e: Exception) {
                fail(if (committed) InstallMessages.SESSION_COMMIT else InstallMessages.SESSION_WRITE)
            } finally {
                // A session never committed is not left staged.
                if (!committed) abandonActive()
            }
        }
    }

    /** The person cancelled: the session, if any, is abandoned. */
    fun cancel() {
        abandonActive()
        _state.value = InstallState.Idle
    }

    /** Back to nothing, once a result has been shown. */
    fun reset() {
        if (_state.value is InstallState.Done || _state.value is InstallState.Failed) _state.value = InstallState.Idle
    }

    private fun abandonActive() {
        val id = sessionId
        sessionId = -1
        if (id < 0) return
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        registry.forget(id)
    }

    private fun fail(status: Int) {
        abandonActive()
        _state.value = InstallState.Failed(InstallMessages.failure(status, null))
    }

    private fun onEvent(event: InstallEvent) {
        if (event.sessionId < 0 || event.sessionId != sessionId) return
        when (event.status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = event.confirm?.let { checked(it) }
                if (confirm == null) {
                    abandonActive()
                    _state.value = InstallState.Failed(
                        InstallFailure("Android asked for a confirmation that Store could not check, so the installation was stopped.")
                    )
                } else {
                    _state.value = InstallState.Confirm(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                registry.forget(event.sessionId)
                sessionId = -1
                _state.value = InstallState.Done(event.packageName ?: packageName)
            }
            else -> {
                registry.forget(event.sessionId)
                sessionId = -1
                _state.value = InstallState.Failed(InstallMessages.failure(event.status, event.message, event.otherPackage))
            }
        }
    }

    /** [intent] if it opens an activity of a system app, stripped of any grant of access to our data; else null. */
    private fun checked(intent: Intent): Intent? {
        val target = intent.resolveActivityInfo(context.packageManager, 0) ?: return null
        if (!isSystem(target.applicationInfo?.flags ?: 0)) return null
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return Intent(intent).apply { flags = flags and grants.inv() }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val SHOWN_STEP = 256 * 1024L

        /** An app of the system image, or an update of one. */
        fun isSystem(flags: Int): Boolean =
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
}
