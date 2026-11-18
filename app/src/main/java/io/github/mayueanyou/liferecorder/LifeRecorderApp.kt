package io.github.mayueanyou.liferecorder

import android.app.Application
import io.github.mayueanyou.liferecorder.screentime.SnapshotWorker
import org.osmdroid.config.Configuration
import java.io.File

class LifeRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        SnapshotWorker.schedule(this)
        io.github.mayueanyou.liferecorder.location.Watchdog.schedule(this)
        // OpenStreetMap asks every app for a name of its own, and map tiles
        // are cached in the app's own storage.
        Configuration.getInstance().apply {
            userAgentValue = "LifeRecorder/0.3 (Android; https://github.com/mayueanyou/LifeRecorder)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
