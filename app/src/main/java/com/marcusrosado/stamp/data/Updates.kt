package com.marcusrosado.stamp.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.marcusrosado.stamp.StampApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A published version of Stamp: its [version] name, where its APK is, and what changed. */
data class Release(val version: String, val apk: String, val size: Long, val notes: String) {
    companion object {
        /**
         * The latest release as GitHub's API describes it, or null when it has no `stamp.apk`
         * or is a draft or prerelease.
         */
        fun parse(json: String): Release? = runCatching {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK_NAME }
                ?: return null
            Release(
                version = o.getString("tag_name").removePrefix("v"),
                apk = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
                notes = notes(o.optString("body")),
            )
        }.getOrNull()

        /**
         * The release text as plain lines: no headings, no changelog link, bullets as dots, and
         * GitHub's generated "by @someone in <link>" left off each line.
         */
        fun notes(body: String): String = body.lines()
            .map { it.trim().replace(CREDIT, "") }
            .filter {
                it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("**Full Changelog") &&
                    !it.startsWith("**New Contributors") && !it.contains("made their first contribution")
            }
            .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }

        private val CREDIT = Regex("""\s+by @\S+ in https://\S+$""")

        const val APK_NAME = "stamp.apk"
    }
}

/** Version names such as 1.2.3, compared part by part as numbers. */
object Versions {
    fun parse(s: String): List<Int>? =
        s.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    /** Whether [candidate] is a later version than [current]. False when either is not a version. */
    fun newer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Where an update stands. */
sealed interface Update {
    /** Not checked yet, checking, or no newer version. */
    data object None : Update
    data class Available(val release: Release) : Update
    data class Downloading(val release: Release, val done: Long, val total: Long) : Update {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    /**
     * Android's installer wants the user to say yes, with [ask]. Started by the app's own screen,
     * never by a receiver: Android blocks that while Stamp is in the background.
     */
    data class Confirm(val release: Release, val ask: Intent) : Update
    /** Handed to Android's installer, which asks the user and then replaces the app. */
    data class Installing(val release: Release) : Update
    data class Failed(val release: Release, val why: String) : Update
}

/**
 * Looks for a newer Stamp among the GitHub releases and installs it from inside the app. The
 * check asks GitHub for the latest release and sends nothing about the user. A copy installed
 * from Play never checks: Play updates it.
 */
class Updates(
    private val app: Context,
    private val scope: CoroutineScope,
    private val current: String,
    /** Whether this build may update itself at all: false for the Play bundle. */
    private val selfUpdate: Boolean,
) {
    private val _state = MutableStateFlow<Update>(Update.None)
    val state: StateFlow<Update> = _state.asStateFlow()

    private val _checking = MutableStateFlow(false)
    /** A check is out to GitHub now. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _reached = MutableStateFlow<Boolean?>(null)
    /** Whether the last check got an answer from GitHub; null before the first. */
    val reached: StateFlow<Boolean?> = _reached.asStateFlow()

    private val dir = File(app.cacheDir, "updates")
    private val promptedFile = File(app.filesDir, "update_prompted.txt")
    private var checkJob: Job? = null
    private var installJob: Job? = null
    private var checked = false
    // A version to install as soon as a check finds it: the user said Update, then Android ended
    // the app while they were turning on the install permission.
    private var wanted: String? = null

    /** Whether this copy can update itself: not from Play, not a build that says no. */
    val possible: Boolean by lazy { selfUpdate && installer() != PLAY }

    /** The version the launch prompt was last shown for, so each version asks once. Reads a file. */
    fun prompted(): String? = runCatching { promptedFile.readText().trim() }.getOrNull()

    fun markPrompted(version: String) {
        scope.launch(Dispatchers.IO) { runCatching { promptedFile.writeText(version) } }
    }

    private val busy: Boolean
        get() = installJob?.isActive == true || _state.value.let { it is Update.Downloading || it is Update.Confirm || it is Update.Installing }

    /**
     * Asks GitHub for the latest release: once a launch, or again when [force]d from Settings.
     * Never while an update is downloading or installing.
     */
    @Synchronized
    fun check(force: Boolean = false) {
        if (!possible || checkJob?.isActive == true || busy) return
        if (checked && !force) return
        checked = true
        checkJob = scope.launch(Dispatchers.IO) {
            _checking.value = true
            val answer = try { latest() } finally { _checking.value = false }
            _reached.value = answer != null
            if (answer == null || busy) return@launch
            val release = answer.release
            if (release != null && Versions.newer(release.version, current)) {
                _state.value = Update.Available(release)
                if (wanted == release.version) { wanted = null; install(release) }
            } else {
                _state.value = Update.None
                dir.deleteRecursively()
            }
        }
    }

    /**
     * Installs [version] once it is known: now if the last check found it, else when the next
     * check does. For a return from Android's settings after the app was ended meanwhile.
     */
    fun installWhenFound(version: String) {
        when (val s = _state.value) {
            is Update.Available -> if (s.release.version == version) install(s.release)
            is Update.Failed -> if (s.release.version == version) install(s.release)
            else -> wanted = version
        }
    }

    /** Downloads [release]'s APK, then hands it to Android's installer. */
    @Synchronized
    fun install(release: Release) {
        if (busy) return
        checkJob?.cancel()
        _state.value = Update.Downloading(release, 0, release.size)
        installJob = scope.launch(Dispatchers.IO) {
            val file = runCatching { download(release) }.getOrNull()
            if (file == null) {
                _state.value = Update.Failed(release, "The download didn't finish. Check the connection and try again.")
                return@launch
            }
            runCatching { commit(file) }
                .onSuccess { _state.value = Update.Installing(release) }
                .onFailure { _state.value = Update.Failed(release, "Android couldn't start the install.") }
        }
    }

    /** Android's installer asks the user, from [UpdateReceiver]: the app's screen shows it. */
    internal fun confirm(ask: Intent) {
        val release = release() ?: return
        _state.value = Update.Confirm(release, ask)
    }

    /** The app's screen started the installer's question. */
    fun confirmShown(release: Release) {
        if ((_state.value as? Update.Confirm)?.release == release) _state.value = Update.Installing(release)
    }

    private fun release(): Release? = when (val s = _state.value) {
        is Update.Installing -> s.release
        is Update.Confirm -> s.release
        is Update.Downloading -> s.release
        is Update.Available -> s.release
        is Update.Failed -> s.release
        Update.None -> null
    }

    /** What the installer said, from [UpdateReceiver]. */
    internal fun result(status: Int, message: String?) {
        val release = release() ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Update.None
            // Cancelled in Android's dialog: the update is still there to install later.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Update.Available(release)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Update.Failed(release, "This copy of Stamp was signed with a different key, so the update can't install over it.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> Update.Failed(release, "The phone is out of space for the update.")
            else -> Update.Failed(release, message ?: "The update didn't install.")
        }
    }

    /** GitHub's answer: [release] is null when the latest one has no APK. */
    private class Answer(val release: Release?)

    /** The latest release, or null when GitHub could not be asked or said no (a rate limit). */
    private fun latest(): Answer? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Stamp")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode !in 200..299) return null
            Answer(Release.parse(c.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun download(release: Release): File? {
        dir.mkdirs()
        val file = File(dir, "stamp-${release.version}.apk")
        if (release.size > 0 && file.length() == release.size) return file
        dir.listFiles()?.forEach { it.delete() }
        val c = URL(release.apk).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Stamp")
        try {
            if (c.responseCode !in 200..299) return null
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            val part = File(dir, "stamp.part")
            var done = 0L
            var shown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // A step every quarter megabyte keeps the bar moving without flooding it.
                        if (done - shown > 256 * 1024) {
                            shown = done
                            _state.value = Update.Downloading(release, done, total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) { part.delete(); return null }
            return file.takeIf { part.renameTo(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            // Lets Android skip its own question when Stamp itself installed the copy it replaces.
            // Needs UPDATE_PACKAGES_WITHOUT_USER_ACTION in the manifest.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite(Release.APK_NAME, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val done = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                session.commit(done.intentSender)
            } catch (e: Exception) {
                // A half-written session would stay staged on the phone: drop it.
                runCatching { session.abandon() }
                throw e
            }
        }
    }

    private fun installer(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
    }.getOrNull()

    companion object {
        const val LATEST = "https://api.github.com/repos/marcusbandit/stamp/releases/latest"
        private const val PLAY = "com.android.vending"
    }
}

/** Hears back from Android's installer: its question for the user, or how the install went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as? StampApp)?.updates ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val ask = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            // The app's screen starts it, now or as soon as the user comes back.
            updates.confirm(ask)
            return
        }
        updates.result(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
