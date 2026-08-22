package de.skilltoremember.app.api

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Liefert den ungefähren Standort als Text fürs `get_location`-Tool. Bewusst
 * ohne Play-Services-Abhängigkeit: Der System-LocationManager reicht für
 * Orts-Genauigkeit, und der eingebaute Geocoder übersetzt die Koordinaten in
 * einen Ortsnamen, ohne dass ein weiterer Cloud-Dienst nötig wäre. Grobe
 * Genauigkeit (ACCESS_COARSE_LOCATION) genügt — es geht um "in welchem Ort",
 * nicht "in welcher Straße".
 */
object LocationProvider {

    /** Ergebnis fürs Modell: Ortsname + Koordinaten, oder eine erklärende Fehlermeldung. */
    fun describe(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "Standort-Berechtigung fehlt — der Nutzer muss sie der App erst erteilen."
        }
        val location = currentLocation(context)
            ?: return "Standort derzeit nicht ermittelbar (kein Standort-Fix — GPS/WLAN prüfen)."
        val coords = "%.4f, %.4f".format(Locale.ROOT, location.latitude, location.longitude)
        val place = placeName(context, location)
        return if (place != null) "$place (Koordinaten: $coords)" else "Koordinaten: $coords"
    }

    /** Frischer Fix mit kurzem Timeout; scheitert der, tut es auch der letzte bekannte. */
    @SuppressLint("MissingPermission") // Berechtigung wird in describe() geprüft; deklariert in den App-Manifesten.
    private fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // "fused" gibt es erst ab API 31, der String schadet davor aber nicht — er ist dann schlicht nicht enabled.
            val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { enabled.contains(it) }
            if (provider != null) {
                var fix: Location? = null
                val latch = CountDownLatch(1)
                runCatching {
                    manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        fix = location
                        latch.countDown()
                    }
                    latch.await(10, TimeUnit.SECONDS)
                }
                if (fix != null) return fix
            }
        }
        return enabled.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun placeName(context: Context, location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) {
            null
        } else {
            @Suppress("DEPRECATION") // synchrone Variante; läuft hier ohnehin auf einem IO-Thread
            val address = Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            address?.let {
                listOfNotNull(it.locality ?: it.subAdminArea, it.adminArea, it.countryName)
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null }
            }
        }
    }.getOrNull()
}
