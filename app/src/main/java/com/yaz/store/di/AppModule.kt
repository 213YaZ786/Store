package com.yaz.store.di

// Ported from Files (YAZ/Files at HEAD, di/AppModule.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, di/AppModule.kt):
// Koin's single as in Koin's Android documentation (https://insert-koin.io/docs/reference/koin-android/start).
// Only the settings and the session registry, which the Application's start-up cleanup reads.
import com.yaz.store.core.apk.ApkInspector
import com.yaz.store.core.install.Installer
import com.yaz.store.core.install.PrefsSessionStore
import com.yaz.store.core.install.SessionRegistry
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.install.InstallService
import com.yaz.store.data.install.StoreBackend
import com.yaz.store.data.net.ReleaseNet
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
    // The notes of a tag are kept in their own folder of the cache, one file per tag.
    single { Catalog(get<ReleaseNet>(), File(androidContext().cacheDir, "notes")) }
    // The install kit's installer and isolated inspector, and the queue that downloads, checks and installs one entry at a time
    // (data/install). Its work runs in the application's own scope, off the main thread; the foreground service starts when an entry is queued.
    single { Installer(androidContext(), get()) }
    single { ApkInspector(androidContext()) }
    single { StoreBackend(androidContext(), get(), get(), get()) }
    single {
        val app = androidContext()
        InstallQueue(
            get<StoreBackend>(),
            File(app.cacheDir, DOWNLOADS),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onQueued = { InstallService.onQueued(app) }
        )
    }
}

/** Where the queue keeps a download until it is checked; the start of the app empties it. */
const val DOWNLOADS = "downloads"
