package com.marcusrosado.stamp.data

import kotlin.math.abs

/** Picks a few photos out of the hundreds a trip can have. */
object BestPhotos {
    /** A photo of the trip: when it was taken, and the stop it belongs to, counted from 0. */
    data class Shot(val id: String, val takenMs: Long, val stop: Int)

    /**
     * Up to [count] of [shots], spread across the trip: each stop in turn gets one, the one
     * furthest in time from those picked already, so the first and last days show too. A photo
     * within [BURST_MS] of one picked is the same moment shot again and never picked.
     */
    fun pick(shots: List<Shot>, count: Int = PREFERRED): List<Shot> {
        if (shots.size <= count) return shots.sortedBy { it.takenMs }
        val byStop = shots.sortedBy { it.takenMs }.groupBy { it.stop }.toSortedMap().values.map { it.toMutableList() }
        val picked = ArrayList<Shot>()
        fun gap(s: Shot) = picked.minOfOrNull { abs(it.takenMs - s.takenMs) } ?: Long.MAX_VALUE
        while (picked.size < count) {
            var added = false
            for (list in byStop) {
                if (picked.size >= count) break
                list.removeAll { gap(it) < BURST_MS }
                // The first of a stop is its middle photo, the most likely to be of the place.
                val next = if (picked.none { it.stop == list.firstOrNull()?.stop }) list.getOrNull(list.size / 2)
                    else list.maxByOrNull(::gap)
                if (next != null) {
                    picked += next
                    list.remove(next)
                    added = true
                }
            }
            if (!added) break
        }
        return picked.sortedBy { it.takenMs }
    }

    /** How many photos a trip shows when the user takes what is picked for them. */
    const val PREFERRED = 4

    /** The most a trip keeps from one pick. */
    const val MAX = 12

    /** Photos closer together than this are one moment. */
    const val BURST_MS = 60_000L
}
