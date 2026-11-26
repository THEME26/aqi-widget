package io.github.theme26.aqibar.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationProvider(private val ctx: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val enabled = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            emptyList()
        }
        val all = try {
            lm.allProviders
        } catch (e: Exception) {
            emptyList()
        }
        return (enabled + all).distinct()
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }
}
