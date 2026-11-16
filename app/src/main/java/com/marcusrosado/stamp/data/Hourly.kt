package com.marcusrosado.stamp.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marcusrosado.stamp.StampApp
import java.util.concurrent.TimeUnit

/**
 * About once an hour, at no fixed minute, notes the phone's rough position for the heat map.
 * Android picks the moment inside each hour to fit its battery saving, so the samples land at
 * random times. Coarse location only, read from the phone's own location service; the point
 * goes into [Seen] and nowhere else.
 */
class Hourly(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StampApp
        if (!app.hourly.on.value || !allowed(applicationContext)) return Result.success()
        // The hourly look is Pro; the sample never talks to Play, so it reads the purchase Play last confirmed.
        if (com.marcusrosado.stamp.BuildConfig.PRO_SOLD && !app.flags.get(ProStore.FLAG)) return Result.success()
        val (lat, lon) = Here.find(applicationContext, maxAge = 20 * 60_000L) ?: return Result.success()
        app.seen.add(listOf(SeenPoint(System.currentTimeMillis(), lat, lon, SeenFrom.Hourly)))
        return Result.success()
    }

    companion object {
        private const val WORK = "hourly-here"

        /** Android 10 and newer only lets an app read the location in the background with this. */
        val BACKGROUND: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null

        fun backgroundGranted(context: Context): Boolean =
            BACKGROUND == null || context.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED

        /** Whether the phone lets Stamp look at where it is while Stamp is closed. */
        fun allowed(context: Context): Boolean = Here.granted(context) && backgroundGranted(context)

        /** Starts the hourly look, or stops it. Starting again keeps the schedule it has. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<Hourly>(1, TimeUnit.HOURS, 50, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
