package io.github.mayueanyou.liferecorder

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * What went wrong, kept on the phone until the user chooses to share it.
 *
 * Crashes are written down as they happen; the location recorder notes when
 * Android refuses it or it fails to start. Nothing here holds a position --
 * only what is needed to find a bug.
 */
object Diagnostics {
    private const val FILE = "diagnostics.log"
    private const val MAX_BYTES = 64 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { note(app, "CRASH on ${thread.name}:\n" + trace(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun note(context: Context, message: String) {
        val file = File(context.filesDir, FILE)
        if (file.exists() && file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES / 2))
        file.appendText("${Instant.now()}  $message\n")
    }

    fun read(context: Context): String = File(context.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()

    fun trace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
