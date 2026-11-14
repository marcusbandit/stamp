package com.marcusrosado.stamp.data

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Saves one file on a background thread, so a tap never waits for the disk. Only the newest
 * contents are kept: a burst of edits made during one write ends in a single write of the last
 * of them, and nothing older ever lands after something newer.
 *
 * Each write goes through [AtomicFile], written beside and synced before it replaces the file,
 * so a crash or a power cut mid-save leaves the last good copy. A write that fails is kept and
 * tried again on the next save or [flush], unless something newer has come in meanwhile.
 *
 * There is one writer per file in the process, so a store opened again on the same file first
 * sees everything the last one saved.
 */
class LatestWriter private constructor(private val file: File) {
    private val lock = Any()

    /** The newest contents not yet taken for writing, as a recipe to make them on this thread. */
    private var pending: (() -> String)? = null

    /** True while a write is queued on the thread and has not yet started. */
    private var queued = false

    /** Held for the whole of a write, so contents are taken and written strictly in order. */
    private val writing = ReentrantLock()

    /** Saves what [contents] makes, soon, replacing anything given before that is not yet on disk. */
    fun submit(contents: () -> String) {
        synchronized(lock) {
            pending = contents
            if (queued) return
            queued = true
        }
        thread.execute { synchronized(lock) { queued = false }; writeNewest() }
    }

    /**
     * Writes anything still waiting, on the calling thread, and returns once it is on disk.
     * False when the write failed; the contents are kept for the next try.
     */
    fun flush(): Boolean = writeNewest()

    private fun writeNewest(): Boolean = writing.withLock {
        val contents = synchronized(lock) { pending.also { pending = null } } ?: return true
        val ok = runCatching { write(contents()) }.isSuccess
        if (!ok) synchronized(lock) { if (pending == null) pending = contents }
        ok
    }

    private fun write(text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray())
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        } catch (e: RuntimeException) {
            atomic.failWrite(out)
            throw e
        }
    }

    companion object {
        private val writers = ConcurrentHashMap<String, LatestWriter>()

        /** One thread for every file: saves are small and land in the order they were asked for. */
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "stamp-save").apply { isDaemon = true } }

        fun of(file: File): LatestWriter = writers.computeIfAbsent(file.absoluteFile.path) { LatestWriter(file.absoluteFile) }
    }
}
