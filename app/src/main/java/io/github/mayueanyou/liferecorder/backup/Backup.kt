package io.github.mayueanyou.liferecorder.backup

import android.content.Context
import io.github.mayueanyou.liferecorder.Diagnostics
import io.github.mayueanyou.liferecorder.data.LifeDb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * My Drive / Life Tracker / 2026-11 / 2026-11-17.json, one file per day,
 * plus places.json. Only a day that changed since it was last written is
 * written again, so a nightly run after the first touches a day or two.
 */
object Backup {
    private const val PREFS = "backup_files"
    private const val ROOT = "Life Tracker"
    // The app was called Life Recorder; a folder made then is found, and renamed, rather than left beside a new one.
    private const val OLD_ROOT = "Life Recorder"

    data class Status(val at: Long, val days: Int, val error: String?)

    fun status(context: Context): Status? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("last_at", 0L).takeIf { it > 0 } ?: return null
        return Status(at, p.getInt("last_days", 0), p.getString("last_error", null))
    }

    private fun note(context: Context, days: Int, error: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_at", System.currentTimeMillis()).putInt("last_days", days).putString("last_error", error).apply()
    }

    /** Writes every changed day. [progress] hears "3 of 40". Returns how many days were written. */
    suspend fun run(context: Context, token: String, progress: (String) -> Unit = {}): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val drive = Drive(token)
        try {
            if (GoogleAccount.email(context) == null) GoogleAccount.remember(context, drive.email())
            val root = prefs.getString("folder:root", null)?.takeIf { exists(drive, it) }
                ?: drive.findFolder(ROOT, null)?.also { prefs.edit().putString("folder:root", it).apply() }
                ?: drive.findFolder(OLD_ROOT, null)?.also { prefs.edit().putString("folder:root", it).apply() }
                ?: drive.folder(ROOT, null).also { prefs.edit().putString("folder:root", it).apply() }
            if (!prefs.getBoolean("root_renamed", false)) {
                runCatching { drive.rename(root, ROOT) }.onSuccess { prefs.edit().putBoolean("root_renamed", true).apply() }
            }

            val days = allDays(context)
            var written = 0
            days.forEachIndexed { i, day ->
                progress("Checking ${i + 1} of ${days.size}")
                val print = DayFile.fingerprint(context, day) ?: return@forEachIndexed
                if (prefs.getString("print:$day", null) == print && day != LocalDate.now()) return@forEachIndexed
                val month = day.toString().substring(0, 7)
                val monthId = prefs.getString("folder:$month", null)
                    ?: drive.folder(month, root).also { prefs.edit().putString("folder:$month", it).apply() }
                val id = try {
                    drive.put("$day.json", monthId, DayFile.write(context, day), prefs.getString("file:$day", null))
                } catch (e: Drive.HttpError) {
                    // The month folder was deleted from Drive: make it again, once.
                    if (e.code != 404) throw e
                    val again = drive.folder(month, root).also { prefs.edit().putString("folder:$month", it).apply() }
                    drive.put("$day.json", again, DayFile.write(context, day), null)
                }
                prefs.edit().putString("file:$day", id).putString("print:$day", print).apply()
                written++
            }
            prefs.edit().putString("file:places",
                drive.put("places.json", root, DayFile.writePlaces(context), prefs.getString("file:places", null))).apply()
            note(context, written, null)
            Diagnostics.note(context, "backup: wrote $written day(s) of ${days.size}")
            return written
        } catch (e: Exception) {
            note(context, 0, e.message ?: e.javaClass.simpleName)
            Diagnostics.note(context, "backup: failed -- ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Reads every day back from Drive into the phone, adding what is missing. Returns how much was added. */
    suspend fun restore(context: Context, token: String, progress: (String) -> Unit = {}): Int {
        val drive = Drive(token)
        val root = drive.findFolder(ROOT, null) ?: drive.findFolder(OLD_ROOT, null) ?: return 0
        var added = 0
        val top = drive.list(root)
        top.firstOrNull { it.name == "places.json" }?.let { added += DayFile.readPlaces(context, drive.download(it.id)) }
        val files = top.filter { it.folder && Regex("\\d{4}-\\d{2}").matches(it.name) }
            .flatMap { m -> drive.list(m.id).filter { !it.folder && it.name.endsWith(".json") } }
        files.forEachIndexed { i, f ->
            progress("Restoring ${i + 1} of ${files.size}")
            added += DayFile.read(context, drive.download(f.id))
        }
        // The day files written next time start afresh, so nothing restored is skipped.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder:root", root).apply()
        Diagnostics.note(context, "backup: restored $added record(s) from ${files.size} day(s)")
        return added
    }

    /** Signing out forgets where the files are, and withdraws the app's access to them. */
    suspend fun signOut(context: Context, token: String?) {
        token?.let { runCatching { Drive.revoke(it) } }
        GoogleAccount.remember(context, null)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun exists(drive: Drive, id: String) = runCatching { drive.list(id); true }.getOrDefault(false)

    private suspend fun allDays(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> {
        val db = LifeDb.get(context)
        val firsts = listOfNotNull(db.fixes().firstTime(), db.money().firstPayment(), db.money().firstItem())
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } +
            listOfNotNull(db.screenTime().firstDay()?.let { LocalDate.parse(it) })
        val first = firsts.minOrNull() ?: return emptyList()
        val today = LocalDate.now(zone)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= today }.toList()
    }
}
