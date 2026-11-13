package com.marcusrosado.stamp.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Finds where a typed address is, with the phone's own geocoder. The owner chose this on
 * 2026-11-13: the typed text goes to the phone's location services, which look it up; Stamp
 * itself has no internet permission and sends nothing else.
 */
object Lookup {
    /** One answer: how the address reads, and where it is. */
    class Found(val label: String, val lat: Double, val lon: Double)

    /**
     * Up to [limit] places matching [query] in the country [alpha2], best first. Empty when the
     * phone has no geocoder, is offline, or finds nothing, so callers fall back to the town.
     */
    suspend fun find(context: Context, query: String, alpha2: String, countryName: String, limit: Int = 4): List<Found> {
        if (query.isBlank() || !Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        // The country's name narrows a street that exists in several countries.
        val text = "$query, $countryName"
        val addresses = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(text, limit, object : Geocoder.GeocodeListener {
                        override fun onGeocode(found: MutableList<Address>) = cont.resume(found)
                        override fun onError(message: String?) = cont.resume(emptyList())
                    })
                }
            } else {
                // The old call blocks and cannot be interrupted, so it runs on its own thread
                // and the timeout stops the waiting, not the call.
                suspendCancellableCoroutine { cont ->
                    Thread {
                        @Suppress("DEPRECATION")
                        val found = runCatching { geocoder.getFromLocationName(text, limit) }.getOrNull().orEmpty()
                        if (cont.isActive) cont.resume(found)
                    }.start()
                }
            }
        }.orEmpty()
        return addresses
            .filter { alpha2.isEmpty() || it.countryCode.equals(alpha2, ignoreCase = true) }
            .filter { it.hasLatitude() && it.hasLongitude() }
            .map { Found(label(it), it.latitude, it.longitude) }
            .distinctBy { it.label }
    }

    /** "Example Street 12, 1234 Sometown": street and number, then postcode and town. */
    private fun label(a: Address): String {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null }
        val town = listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea).joinToString(" ").ifBlank { null }
        return listOfNotNull(street, town).joinToString(", ").ifBlank { a.getAddressLine(0) ?: a.featureName ?: "" }
    }
}
