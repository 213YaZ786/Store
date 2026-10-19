package com.yaz.store.di

// Ported from Files (YAZ/Files at HEAD, di/AppModule.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, di/AppModule.kt):
// Koin's single as in Koin's Android documentation (https://insert-koin.io/docs/reference/koin-android/start).
// Only the settings and the session registry, which the Application's start-up cleanup reads.
import com.yaz.store.core.apk.ApkInspector
import com.yaz.store.core.install.Installer
import com.yaz.store.core.install.PrefsSessionStore
import com.yaz.store.core.install.SessionRegistry
import com.yaz.store.BuildConfig
import android.content.pm.PackageManager
import android.os.Build
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.CatalogOverlay
import com.yaz.store.data.catalog.Installed
import com.yaz.store.data.door.Door
import com.yaz.store.data.door.DoorGuard
import com.yaz.store.data.door.DoorProvider
import com.yaz.store.data.door.Staging
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.catalog.Seeded
import com.yaz.store.data.catalog.phoneAbis
import com.yaz.store.data.catalog.phoneLocaleTags
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.install.InstallService
import com.yaz.store.data.install.StoreBackend
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.sparse.SparseApk
import com.yaz.store.data.sparse.SparseInspector
import com.yaz.store.data.sparse.SparseProbe
import com.yaz.store.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/** Single composition root. */
val appModule = module {
    single { SettingsStore(androidContext()) }
    single { SessionRegistry(PrefsSessionStore(androidContext())) }
    // The catalog of our apps, read from GitHub through the one door of the network (data/net, data/catalog).
    single { ReleaseNet(androidContext().cacheDir) }
    // The date and sizes of a tag are kept in their own folder of the cache, one file per tag.
    // In a debug build, entries a test pushes to files/debug-seed.json (data/catalog/Seeded) stand beside the live ones.
    single {
        val app = androidContext()
        Catalog(
            get<ReleaseNet>(), File(app.cacheDir, "details"),
            seeded = if (BuildConfig.DEBUG) ({ Seeded.read(File(app.filesDir, "debug-seed.json"), phoneLocaleTags(), phoneAbis()) }) else ({ emptyList() }),
            // A release with no app.json is read from its APK by ranges, in the isolated SparseInspectorService (data/sparse).
            overlay = if (BuildConfig.DEBUG) debugOverlay(app) else null,
            probe = SparseProbe(get<ReleaseNet>()::ranges, SparseApk(SparseInspector(app), File(app.cacheDir, "sparse-work")), File(app.cacheDir, "sparse"))
        )
    }
    // The release icons of apps not installed, one file per tag in the cache.
    single { IconStore(get<ReleaseNet>(), File(androidContext().cacheDir, "icons"), if (BuildConfig.DEBUG) debugOverlay(androidContext()) else null) }
    // The install kit's installer and isolated inspector, and the queue that downloads, checks and installs one entry at a time
    // (data/install). Its work runs in the application's own scope, off the main thread; the foreground service starts when an entry is queued.
    single { Installer(androidContext(), get()) }
    single { ApkInspector(androidContext()) }
    single { StoreBackend(androidContext(), get(), get(), get(), wifiOnly = { get<SettingsStore>().current.wifiOnly }) }
    single {
        val app = androidContext()
        InstallQueue(
            get<StoreBackend>(),
            File(app.cacheDir, DOWNLOADS),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onQueued = { InstallService.onQueued(app) }
        )
    }
    // The door (data/door): the update kit's "opened" and "left" calls. Its own installer, so a door install never meets the queue's
    // session (each Installer follows its own session id); the staged files live in the downloads folder.
    single {
        val app = androidContext()
        DoorGuard(
            signersOf = { Installed.read(app, it)?.signers },
            sameSignerAsStore = { app.packageManager.checkSignatures(app.packageName, it) == PackageManager.SIGNATURE_MATCH },
            debug = BuildConfig.DEBUG
        )
    }
    single {
        val app = androidContext()
        val backend = StoreBackend(app, get(), get(), Installer(app, get()), wifiOnly = { get<SettingsStore>().current.wifiOnly })
        Staging(backend, File(app.cacheDir, DOWNLOADS), installedVersion = { Installed.read(app, it)?.versionCode })
    }
    single {
        val app = androidContext()
        Door(
            get(), get(),
            load = { get<Catalog>().load().entries },
            statusOf = { entry, caller ->
                Door.status(entry, caller, Installed.read(app, caller.pkg), Build.VERSION.SDK_INT, app.packageName)
            },
            log = DoorProvider::debugLog
        )
    }
}

// The debug build's overlay (src/debug DebugMeta) found by name (Class.forName, https://developer.android.com/reference/java/lang/Class#forName(java.lang.String)); null when it is not there.
private fun debugOverlay(app: android.content.Context): CatalogOverlay? = runCatching {
    Class.forName("com.yaz.store.DebugMeta").getConstructor(File::class.java).newInstance(app.filesDir) as CatalogOverlay
}.getOrNull()

/** Where the queue keeps a download until it is checked; the start of the app empties it. */
const val DOWNLOADS = "downloads"
