package com.marcusrosado.stamp.ui

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Decoded photos and video frames for the whole app, by picture and size. A tile that scrolls
 * back into view, a card opened again or a visit summary showing the same photo finds it here
 * and draws it on its first frame instead of decoding it again.
 */
object Thumbs {
    /** An eighth of what the app may hold, counted in bytes, so a long trip never runs it short. */
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    /**
     * A full-screen photo can be tens of megabytes; one that big would push out every thumbnail
     * for a picture that is opened once, so it is decoded each time instead.
     */
    private val largest = cache.maxSize() / 4

    fun key(uri: String, video: Boolean, px: Int, crop: Boolean) = "$px|$crop|$video|$uri"

    operator fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        if (image.asAndroidBitmap().allocationByteCount <= largest) cache.put(key, image)
    }
}
