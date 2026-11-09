package app.nida

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * One-shot location, only when asked (opening the app or tapping the widget's city).
 * It never listens in the background, and reuses a recent fix instead of waking the radio.
 */
object Locator {
    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun current(context: Context, done: (Location?) -> Unit) {
        if (!granted(context)) return done(null)
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH) return done(last)

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return done(last)
        }
        val main = Handler(Looper.getMainLooper())
        val cancel = CancellationSignal()
        var finished = false
        lateinit var listener: LocationListener
        fun finish(location: Location?) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            cancel.cancel()
            if (Build.VERSION.SDK_INT < 30) lm.removeUpdates(listener)
            done(location ?: last)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Needed below Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        main.postDelayed({ finish(null) }, TIMEOUT)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, cancel, context.mainExecutor) { finish(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            finish(null)
        }
    }

    /** The city's name, from the phone's geocoder or, failing that, a free reverse-geocoding service. Blocking. */
    fun cityName(context: Context, lat: Double, lng: Double): String? {
        val lang = Lang.current(context)
        runCatching {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale(lang)).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                    (a.locality ?: a.subAdminArea ?: a.adminArea)?.let { return it }
                }
            }
        }
        runCatching {
            val o = Net.json("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lng&localityLanguage=$lang")
            return o.optString("city").ifEmpty { o.optString("locality") }.ifEmpty { null }
        }
        return null
    }

    private const val FRESH = 10 * 60_000L
    private const val TIMEOUT = 12_000L
}
