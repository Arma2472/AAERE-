package com.example.betterauto.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class WeatherCondition(val label: String) {
    CLEAR("Ciel dégagé"),
    PARTLY_CLOUDY("Éclaircies"),
    FOG("Brouillard"),
    DRIZZLE("Bruine"),
    RAIN("Pluie"),
    SNOW("Neige"),
    STORM("Orage")
}

data class WeatherState(
    val cityName: String = "Paris",
    val temperatureC: Int? = null,
    val apparentTempC: Int? = null,
    val windSpeedKmh: Int? = null,
    val condition: WeatherCondition = WeatherCondition.PARTLY_CLOUDY,
    val isDaylight: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Fetches live automotive weather using Open-Meteo (no API key required)
 * with Android 10 compatible coarse LocationManager lookup or city geocoding.
 */
class WeatherRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchWeather(preferredCity: String): WeatherState = withContext(Dispatchers.IO) {
        try {
            val coarseCoords = getLastKnownCoarseLocation()
            val (lat, lon, resolvedName) = if (coarseCoords != null && preferredCity.isBlank()) {
                Triple(coarseCoords.latitude, coarseCoords.longitude, "Position GPS")
            } else {
                geocodeCity(preferredCity.ifBlank { "Paris" })
                    ?: if (coarseCoords != null) {
                        Triple(coarseCoords.latitude, coarseCoords.longitude, "Position GPS")
                    } else {
                        Triple(48.8566, 2.3522, "Paris")
                    }
            }

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
                "&timezone=auto"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext WeatherState(
                        cityName = resolvedName,
                        errorMessage = "Météo indisponible (HTTP ${response.code})"
                    )
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val current = root.getJSONObject("current")
                val temp = current.getDouble("temperature_2m").roundToInt()
                val apparent = current.getDouble("apparent_temperature").roundToInt()
                val wind = current.getDouble("wind_speed_10m").roundToInt()
                val isDay = current.optInt("is_day", 1) == 1
                val wmoCode = current.optInt("weather_code", 2)

                WeatherState(
                    cityName = resolvedName,
                    temperatureC = temp,
                    apparentTempC = apparent,
                    windSpeedKmh = wind,
                    condition = mapWmoCode(wmoCode),
                    isDaylight = isDay,
                    isLoading = false,
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            WeatherState(
                cityName = preferredCity.ifBlank { "Paris" },
                isLoading = false,
                errorMessage = "Hors-ligne · météo non actualisée"
            )
        }
    }

    private fun geocodeCity(city: String): Triple<Double, Double, String>? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=fr&format=json"
        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val results = json.optJSONArray("results") ?: return@use null
                if (results.length() == 0) return@use null
                val first = results.getJSONObject(0)
                Triple(
                    first.getDouble("latitude"),
                    first.getDouble("longitude"),
                    first.optString("name", city)
                )
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownCoarseLocation(): Location? {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        return best
    }

    private fun mapWmoCode(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.CLEAR
        1, 2, 3 -> WeatherCondition.PARTLY_CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57 -> WeatherCondition.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.STORM
        else -> WeatherCondition.PARTLY_CLOUDY
    }
}
