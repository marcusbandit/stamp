package com.marcusrosado.stamp.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, roughly: enough to name the country, never more. Coarse location only,
 * asked from the phone's own location service, so nothing leaves the device.
 */
object Here {
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The phone's position as latitude and longitude, or null without permission or a fix. A
     * fix younger than [maxAge] is used as it is: six hours names a country, a dot on the map
     * wants minutes. Otherwise it asks for a fresh one and waits up to ten seconds.
     */
    suspend fun find(context: Context, maxAge: Long = SIX_HOURS): Pair<Double, Double>? {
        if (!granted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < maxAge }
            .maxByOrNull { it.time }
        if (recent != null) return recent.latitude to recent.longitude
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { it in providers } ?: return null
        val fresh = withTimeoutOrNull(10_000) { current(context, lm, provider) }
        return fresh?.let { it.latitude to it.longitude }
    }

    private suspend fun current(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                } else {
                    val listener = android.location.LocationListener { cont.resume(it) }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, context.mainLooper)
                }
            } catch (e: SecurityException) {
                cont.resume(null)
            }
        }

    const val SIX_HOURS = 6L * 60 * 60 * 1000
    const val TWO_MINUTES = 2L * 60 * 1000
}
