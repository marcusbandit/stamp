package com.marcusrosado.stamp.data

import org.json.JSONObject
import java.io.File

/**
 * Save files that [VisitStore] could not read and moved aside, as `visits.json.unreadable-<ms>`.
 * Each one stays until the user saves a copy out or restores from it. Then it is renamed, never
 * deleted, so the bytes are still there if anything went wrong.
 */
object SetAside {
    /** The set-aside copies of [store] not dealt with yet, newest first. */
    fun find(store: File): List<File> {
        val name = Regex(Regex.escape(store.name) + """\.unreadable-(\d+)""")
        return store.parentFile?.listFiles().orEmpty()
            .mapNotNull { f -> name.matchEntire(f.name)?.let { f to it.groupValues[1].toLongOrNull() } }
            .sortedByDescending { it.second ?: 0L }
            .map { it.first }
    }

    /** When [f] was set aside, in epoch ms, read from its name. */
    fun at(f: File): Long = f.name.substringAfterLast('-').toLongOrNull() ?: f.lastModified()

    /** Whether [text] was saved by a newer Stamp than this one, rather than being damaged. */
    fun fromNewer(text: String): Boolean =
        runCatching { JSONObject(text).optInt("version") > FILE_VERSION }.getOrDefault(false)

    /** Marks [f] dealt with, so no launch asks about it again. */
    fun handled(f: File): Boolean = f.renameTo(File(f.parentFile, "${f.name}.handled"))
}
