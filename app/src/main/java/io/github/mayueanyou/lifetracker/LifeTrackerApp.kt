package io.github.mayueanyou.lifetracker

import android.app.Application
import io.github.mayueanyou.lifetracker.screentime.SnapshotWorker
import org.osmdroid.config.Configuration
import java.io.File

class LifeTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        SnapshotWorker.schedule(this)
        io.github.mayueanyou.lifetracker.location.Watchdog.schedule(this)
        if (io.github.mayueanyou.lifetracker.backup.GoogleAccount.signedIn(this)) {
            io.github.mayueanyou.lifetracker.backup.BackupWorker.schedule(this)
        }
        // OpenStreetMap asks every app for a name of its own, and map tiles
        // are cached in the app's own storage.
        Configuration.getInstance().apply {
            userAgentValue = "LifeTracker/0.9 (Android; https://github.com/mayueanyou/LifeTracker)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
