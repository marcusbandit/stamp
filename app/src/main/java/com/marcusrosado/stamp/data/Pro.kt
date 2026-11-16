package com.marcusrosado.stamp.data

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Stamp Pro: rail and road lines, and the heat map with its hourly look. A build made from
 * source has every feature; only the Play build sells Pro, as a one-time purchase. Each build
 * picks its own [ProStore] from `src/full` or `src/play`.
 */
interface ProStore {
    /** Whether this build sells Pro at all. False in a build made from source. */
    val sold: Boolean

    /** True when Pro is the user's: always in a build from source, after the purchase on Play. */
    val unlocked: StateFlow<Boolean>

    /** The price as Play shows it, once known. */
    val price: StateFlow<String?>

    /** Opens Play's purchase sheet; false when Play cannot sell it right now, offline say. */
    fun buy(activity: Activity): Boolean

    /** Asks Play again what the user owns, for a purchase made on another phone. */
    fun refresh()

    companion object {
        /**
         * The flag file that remembers a purchase Play confirmed, so Pro is there at once on the
         * next launch, offline too, and in the hourly sample, which never talks to Play.
         */
        const val FLAG = "pro"
    }
}
