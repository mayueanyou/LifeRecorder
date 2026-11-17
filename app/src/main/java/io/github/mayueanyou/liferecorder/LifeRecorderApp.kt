package io.github.mayueanyou.liferecorder

import android.app.Application
import io.github.mayueanyou.liferecorder.screentime.SnapshotWorker

class LifeRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SnapshotWorker.schedule(this)
    }
}
