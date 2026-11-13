package com.marcusrosado.stamp.scan

import com.marcusrosado.stamp.data.Distance
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video on this phone that might belong to a stop. */
data class Suggestion(val uri: Uri, val video: Boolean, val takenMs: Long)

/**
 * Photos and videos for memories: suggestions from this phone, lasting access to what the
 * user picks from the gallery, and thumbnails. Everything is read on the device.
 */
object Memories {
    /**
     * Photos and videos taken between [fromMs] and [toMs], newest last. When [near] is given,
     * photos with a location further than [RADIUS_KM] from it are left out; photos and videos
     * without a location stay, since the date already says they are likely from the trip.
     */
    suspend fun suggest(
        context: Context,
        fromMs: Long,
        toMs: Long,
        near: Pair<Double, Double>?,
    ): List<Suggestion> = withContext(Dispatchers.IO) {
        val out = ArrayList<Suggestion>()
        out += query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fromMs, toMs, video = false)
        out += query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fromMs, toMs, video = true)
        out.sortBy { it.takenMs }
        // A long trip has hundreds of photos: look at an even spread across the whole window,
        // so the last days get suggestions too, and only that many EXIF reads happen.
        val spread = evenly(out, if (near == null) LIMIT else LIMIT * 3)
        if (near == null) return@withContext spread
        val kept = ArrayList<Suggestion>()
        for (s in spread) {
            val at = if (s.video) null else location(context, s.uri)
            if (at == null || Distance.km(at, near) <= RADIUS_KM) kept += s
        }
        evenly(kept, LIMIT)
    }

    private fun query(context: Context, collection: Uri, fromMs: Long, toMs: Long, video: Boolean): List<Suggestion> {
        val taken = MediaStore.MediaColumns.DATE_TAKEN
        val out = ArrayList<Suggestion>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, taken),
                "$taken >= ? AND $taken <= ?",
                arrayOf(fromMs.toString(), toMs.toString()),
                "$taken ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val takenCol = c.getColumnIndexOrThrow(taken)
                while (c.moveToNext()) {
                    out += Suggestion(ContentUris.withAppendedId(collection, c.getLong(idCol)), video, c.getLong(takenCol))
                }
            }
        }
        return out
    }

    private fun <T> evenly(list: List<T>, n: Int): List<T> =
        if (list.size <= n) list else List(n) { i -> list[(i.toLong() * list.size / n).toInt()] }

    /** Gives back the lasting grant on an item no memory uses any more. */
    private fun release(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Releases every lasting grant that no memory points at, such as those of a removed visit.
     * Android caps how many an app may hold and drops the oldest past that.
     */
    fun releaseUnused(context: Context, inUse: Set<String>) {
        for (p in context.contentResolver.persistedUriPermissions) {
            if (p.uri.toString() !in inUse) release(context, p.uri)
        }
    }

    private fun location(context: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(original)?.use { s ->
            // 0,0 is a camera with no fix, not a photo from the Gulf of Guinea.
            ExifInterface(s).latLong?.takeIf { it[0] != 0.0 || it[1] != 0.0 }?.let { it[0] to it[1] }
        }
    }.getOrNull()


    /**
     * Keeps access to a picked item across restarts. The system picker hands out a grant that
     * can be made lasting; an item from the phone's own gallery needs none.
     */
    fun keep(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    /** When a picked item was taken, from the gallery's own record or the photo's EXIF. */
    fun takenMs(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** A small picture of a photo or a video's first frame, or null when it is gone. */
    suspend fun thumbnail(context: Context, uri: Uri, video: Boolean, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } else if (video) {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, uri)
                        getFrameAtTime(0)
                    } finally {
                        release()
                    }
                }
            } else {
                decode(context, uri, px)
            }
        }.getOrNull()
    }

    /** A photo sized to fit [px] on its longer side, for the full-screen view. */
    suspend fun picture(context: Context, uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(context, uri, px) }.getOrNull()
    }

    private fun decode(context: Context, uri: Uri, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Plays a video in the phone's own player. Videos never start inside Stamp. */
    fun play(context: Context, uri: Uri) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    /** How far from a stop a photo with a location may be and still be suggested for it. */
    private const val RADIUS_KM = 40.0

    /** At most this many suggestions, spread over the visit. */
    private const val LIMIT = 60
}
