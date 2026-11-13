package com.marcusrosado.stamp

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps a report of every crash on the phone: when, which build, which device, the stack trace,
 * how full the memory was and the app's own last log lines. Reports stay in the app's external
 * files folder, where adb can read them, and leave the phone only when the user shares them from
 * Settings. The last [KEEP] are kept.
 */
object CrashLog {
    private const val KEEP = 20

    // A little memory set aside so a report can still be written when the app has run out.
    @Volatile private var reserve: ByteArray? = ByteArray(256 * 1024)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            reserve = null
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")

    /** The kept reports, newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name }

    /** The newest few reports as one text for sharing, kept small enough to hand to another app. */
    fun text(context: Context): String = reports(context).take(3).joinToString("\n\n") { it.readText() }.take(50_000)

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = dir(context).apply { mkdirs() }
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)
        val rt = Runtime.getRuntime()
        val report = buildString {
            appendLine("Stamp crash, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(now)}")
            appendLine("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread ${thread.name}")
            appendLine("Memory ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB")
            appendLine()
            appendLine(error.stackTraceToString())
            appendLine("Last log lines:")
            append(ownLog())
        }
        File(dir, "crash-$stamp.txt").writeText(report)
        reports(context).drop(KEEP).forEach { it.delete() }
    }

    /**
     * The app's own last log lines; an app may always read its own. Given two seconds at most,
     * so a stuck logcat never keeps a crashing app from ending.
     */
    private fun ownLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "300", "--pid=${Process.myPid()}")
            .redirectErrorStream(true).start()
        var out = "(not readable)\n"
        val reader = Thread { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault(out) }
        reader.isDaemon = true
        reader.start()
        reader.join(2_000)
        if (!p.waitFor(100, TimeUnit.MILLISECONDS)) p.destroy()
        out
    }.getOrDefault("(not readable)\n")
}
