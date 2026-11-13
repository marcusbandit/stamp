package com.marcusrosado.stamp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The rail and road packs of the countries the user's trips go through. A pack is fetched once
 * from the public data repository when a country first needs one and kept on the phone, outside
 * the user's backup: it is map data, not theirs, and can always be fetched again. Only the
 * country's two-letter code is asked for; nothing about the user is sent.
 */
class RoutePacks(private val dir: File, private val scope: CoroutineScope) {
    private val _net = MutableStateFlow<RouteNet?>(null)

    /** The countries wanted so far, merged into one network; null before any is loaded. */
    val net: StateFlow<RouteNet?> = _net.asStateFlow()

    private val lock = Mutex()
    private var loaded: Map<String, RouteNet> = emptyMap()
    // Countries with no pack on the server, or one that would not read; asked for once a launch.
    private val missing = HashSet<String>()

    /**
     * Makes sure the packs for [codes] (ISO alpha-2) are on the phone and in [net]: those kept
     * load from storage and show at once, then the rest download one at a time, each showing
     * as soon as it is in. A country that fails is not asked for again until the next launch.
     */
    fun want(codes: Set<String>) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val need = codes.map { it.uppercase() }.filter { it.length == 2 && it !in loaded && it !in missing }
                if (need.isEmpty()) return@withLock
                dir.mkdirs()
                val (kept, fetch) = need.partition { File(dir, "$it.bin.gz").exists() }
                kept.forEach { load(it) }
                publish()
                for (cc in fetch) {
                    val file = File(dir, "$cc.bin.gz")
                    if (!fetch(cc, file)) { missing += cc; continue }
                    if (load(cc)) publish()
                }
            }
        }
    }

    private fun load(cc: String): Boolean {
        val file = File(dir, "$cc.bin.gz")
        val net = runCatching { RouteNet.read(file.readBytes()) }.getOrNull()
        if (net == null) {
            file.delete()
            missing += cc
            return false
        }
        loaded = loaded + (cc to net)
        return true
    }

    private fun publish() {
        if (loaded.isNotEmpty()) _net.value = RouteNet.merge(loaded.values.toList())
    }

    /** Downloads one country's pack into [file]; false when there is none or it failed. */
    private fun fetch(cc: String, file: File): Boolean =
        runCatching {
            val c = URL("$BASE$cc.bin.gz").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Stamp")
            try {
                if (c.responseCode !in 200..299) return@runCatching false
                val part = File(dir, "$cc.part")
                c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(file)
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)

    /** Throws away every kept pack, when the user switches following lines off. */
    fun clear() {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                dir.listFiles()?.forEach { it.delete() }
                loaded = emptyMap()
                missing.clear()
                _net.value = null
            }
        }
    }

    companion object {
        const val BASE = "https://raw.githubusercontent.com/marcusbandit/stamp-data/main/routes/v1/"
    }
}
