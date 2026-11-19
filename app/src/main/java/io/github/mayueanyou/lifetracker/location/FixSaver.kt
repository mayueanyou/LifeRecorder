package io.github.mayueanyou.lifetracker.location

import android.content.Context
import android.location.Location
import io.github.mayueanyou.lifetracker.Diagnostics
import io.github.mayueanyou.lifetracker.data.FixRow
import io.github.mayueanyou.lifetracker.data.LifeDb
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.withLock

/**
 * Where every position goes, whichever way it arrived -- the recorder's own
 * callback, Android's delivery to [LocationReceiver], or the watchdog. A
 * position already saved (the same moment, from another route) is skipped,
 * so the routes can overlap freely.
 */
object FixSaver {
    private val counts = HashMap<String, AtomicInteger>()
    private var hourStarted = System.currentTimeMillis()

    // One position can come by both routes at the same moment; checked and saved
    // one at a time, it is saved once.
    private val saving = kotlinx.coroutines.sync.Mutex()

    suspend fun save(context: Context, locations: List<Location>, via: String) = saving.withLock { saveNow(context, locations, via) }

    private suspend fun saveNow(context: Context, locations: List<Location>, via: String) {
        val dao = LifeDb.get(context).fixes()
        var saved = 0
        for (l in locations) {
            if (dao.countAt(l.time) > 0) continue
            dao.insert(
                FixRow(
                    time = l.time, latitude = l.latitude, longitude = l.longitude,
                    accuracy = if (l.hasAccuracy()) l.accuracy else null,
                    altitude = if (l.hasAltitude()) l.altitude else null,
                    speed = if (l.hasSpeed()) l.speed else null,
                    bearing = if (l.hasBearing()) l.bearing else null,
                    provider = via,
                ),
            )
            saved++
        }
        Recording.noteFix(context)
        synchronized(counts) {
            counts.getOrPut(via) { AtomicInteger() }.addAndGet(saved)
            // Once an hour, how many arrived and by which route: the record
            // that tells a recorder that is quiet from one that is broken.
            if (System.currentTimeMillis() - hourStarted >= 60 * 60 * 1000L) {
                Diagnostics.note(context, "positions in the last hour: " +
                    counts.entries.joinToString { "${it.key}=${it.value.get()}" })
                counts.clear()
                hourStarted = System.currentTimeMillis()
            }
        }
    }
}
