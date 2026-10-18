package com.yaz.store

// Ported from Files (YAZ/Files at HEAD, FilesApplication.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, CalculatorApplication.kt).
// The start-up cleanup is App Manager's (GPL-3.0-or-later): PackageInstallerSessionRegistry.cleanupOrphanedSessions
// at the opening of a session, here at the start of the app, and the copies of an attempt deleted
// (https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerSessionRegistry.java).
// Left out of Files' version: the Safe folder and the saved files' origins.
import android.app.Application
import com.yaz.store.core.apk.ApkBundle
import com.yaz.store.core.install.SessionRegistry
import com.yaz.store.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class StoreApplication : Application() {
    // Files' FilesApplication.onCreate.
    override fun onCreate() {
        super.onCreate()
        // Nothing of the app starts in an isolated process.
        if (android.os.Process.isIsolated()) return
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@StoreApplication)
            modules(appModule)
        }.koin
        // What a crash or a killed process left of an install: the copies and the sessions. Once, off the main thread.
        val registry = koin.get<SessionRegistry>()
        Thread {
            ApkBundle.sweep(cacheDir)
            // A download a killed process left half way (the queue deletes its own after each try).
            java.io.File(cacheDir, com.yaz.store.di.DOWNLOADS).deleteRecursively()
            registry.abandonOrphans(packageManager.packageInstaller)
        }.start()
    }
}
