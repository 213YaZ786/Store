package com.yaz.store.core.install

import android.content.Context
import android.content.pm.PackageInstaller
import java.util.UUID

// Ported from App Manager's PackageInstallerSessionRegistry (GPL-3.0-or-later): the ids of the sessions the app
// opens are kept with a token of the running process, and at the next start every one of them still alive in
// PackageInstaller.getMySessions() is abandoned, so a crash does not leave staged files behind:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerSessionRegistry.java
// and its test, PackageInstallerSessionRegistryTest. Changes: the preferences behind an interface so the rule runs
// on the JVM, and only sessions of this registry are touched, never the app's update session (core/update).

/** Where the registry keeps its records. */
interface SessionStore {
    fun all(): Map<String, String>
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** SharedPreferences, committed at once as App Manager does. */
class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun all(): Map<String, String> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    private companion object {
        const val NAME = "package_installer_sessions"
    }
}

class SessionRegistry(private val store: SessionStore, private val token: String = UUID.randomUUID().toString()) {

    /** A session was opened by this process. */
    @Synchronized
    fun record(sessionId: Int) {
        if (sessionId < 0) return
        store.put(KEY_PREFIX + sessionId, token)
    }

    /** A session ended, whatever the way. */
    @Synchronized
    fun forget(sessionId: Int) {
        if (sessionId < 0) return
        store.remove(KEY_PREFIX + sessionId)
    }

    /**
     * Abandons the sessions an earlier process opened and left, among [liveIds] (what the installer still holds).
     * A record whose session is gone is dropped; one that cannot be abandoned stays for the next start.
     */
    @Synchronized
    fun abandonOrphans(liveIds: () -> Set<Int>, abandon: (Int) -> Unit) {
        val records = store.all()
        if (records.isEmpty()) return
        val live = try {
            liveIds()
        } catch (e: Exception) {
            return
        }
        for ((key, owner) in records) {
            if (!key.startsWith(KEY_PREFIX) || owner == token) continue
            val id = key.removePrefix(KEY_PREFIX).toIntOrNull()
            if (id == null || id !in live) {
                store.remove(key)
                continue
            }
            try {
                abandon(id)
                store.remove(key)
            } catch (e: Exception) {
                // Still tracked, tried again at the next start.
            }
        }
    }

    /** The cleanup against the real installer. */
    fun abandonOrphans(installer: PackageInstaller) {
        abandonOrphans(
            liveIds = { installer.mySessions.map { it.sessionId }.toSet() },
            abandon = { installer.abandonSession(it) }
        )
    }

    companion object {
        const val KEY_PREFIX = "session_"
    }
}
