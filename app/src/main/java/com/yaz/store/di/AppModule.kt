package com.yaz.store.di

// Ported from Files (YAZ/Files at HEAD, di/AppModule.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, di/AppModule.kt):
// Koin's single as in Koin's Android documentation (https://insert-koin.io/docs/reference/koin-android/start).
// Only the settings and the session registry, which the Application's start-up cleanup reads.
import com.yaz.store.core.install.PrefsSessionStore
import com.yaz.store.core.install.SessionRegistry
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.settings.SettingsStore
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
}
