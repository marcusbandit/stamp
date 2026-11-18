package com.marcusrosado.stamp.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.marcusrosado.stamp.StampApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** The folder automatic backups go to: the tree Android granted, and its name as shown. */
data class BackupFolder(val uri: String, val name: String)

/**
 * Saves a backup by itself into the folder the user picked, through the grant Android gave
 * for it: once a week, and a while after a change once the last backup is a day old. One file
 * a day, the newest few kept. It writes the same file Settings, Save a backup does, and only
 * ever to that folder: nothing goes anywhere the user did not choose.
 */
class AutoBackup(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StampApp
        val folder = app.backupFolder.value ?: run { schedule(applicationContext, false); return Result.success() }
        val visits = app.visitsLoading.await()
        // A save file that could not be read leaves the store empty until the user deals with
        // it; backing that up would push the good backups out of the folder. So would an empty
        // store, which has nothing to keep.
        if (visits.damaged || app.setAside.value.isNotEmpty() || visits.visits.value.isEmpty()) return Result.success()
        val now = System.currentTimeMillis()
        if (now - app.lastAutoBackup.value < inputData.getLong(KEY_MIN_AGE, 0L)) return Result.success()
        val ok = withContext(Dispatchers.IO) {
            runCatching { write(applicationContext, Uri.parse(folder.uri), fileName(LocalDate.now()), app.backupText()) }.getOrDefault(false)
        }
        if (!ok) return if (runAttemptCount < 3) Result.retry() else Result.success()
        app.autoBackedUp(now)
        return Result.success()
    }

    companion object {
        private const val WEEKLY = "auto-backup-weekly"
        private const val SOON = "auto-backup-soon"
        private const val KEY_MIN_AGE = "min_age"
        const val DAY_MS = 24 * 3_600_000L

        /** How many of its own backups Stamp keeps in the folder; older ones go. */
        const val KEEP = 5

        private const val PREFIX = "stamp-auto-backup-"
        private val OWN = Regex("""^stamp-auto-backup-\d{4}-\d{2}-\d{2}\.json$""")

        /** Today's file: a second backup the same day replaces the first. */
        fun fileName(day: LocalDate) = "$PREFIX$day.json"

        /**
         * The files to delete from a folder holding [names], to keep the newest [keep] of
         * Stamp's own. Any other file in the folder is the user's and is never touched.
         */
        fun stale(names: List<String>, keep: Int = KEEP): List<String> =
            names.filter { OWN.matches(it) }.distinct().sortedDescending().drop(keep)

        /** Starts the weekly backup, or stops every backup to come. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WEEKLY); wm.cancelUniqueWork(SOON); return }
            // Six days, not seven: a run Android moves a little later still counts as the week's.
            val req = PeriodicWorkRequestBuilder<AutoBackup>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_MIN_AGE to 6 * DAY_MS))
                .build()
            wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** A backup right away, as when the folder was just picked. */
        fun now(context: Context) = once(context, 0L, 0L, ExistingWorkPolicy.REPLACE)

        /**
         * A backup a while after a change, so a run of edits is saved once, and only when the
         * last backup is a day old by then. A change while one is waiting adds nothing.
         */
        fun afterChange(context: Context) = once(context, DAY_MS, 15, ExistingWorkPolicy.KEEP)

        private fun once(context: Context, minAge: Long, delayMinutes: Long, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<AutoBackup>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MIN_AGE to minAge))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, policy, req)
        }

        /** The folder's name as the files app shows it, or null when it cannot be read. */
        fun nameOf(context: Context, tree: Uri): String? = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

        /** Writes [text] as [name] into the folder, then lets the oldest of Stamp's own files go. */
        private fun write(context: Context, tree: Uri, name: String, text: String): Boolean {
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val files = HashMap<String, String>()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) files[c.getString(1) ?: continue] = c.getString(0) }
            val target = files[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeId), "application/json", name)
                ?: return false
            resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: return false
            // Only once the new file is there do the old ones go.
            for (old in stale(files.keys.toList() + name)) {
                files[old]?.let { id -> runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } }
            }
            return true
        }
    }
}
