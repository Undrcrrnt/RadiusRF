package app.radiusrf.features.maps

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveillanceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun load(lat: Double, lon: Double, force: Boolean = false): Result<List<SurveillanceSite>> = withContext(Dispatchers.IO) {
        runCatching {
            val pad = 0.04
            val cache = File(context.filesDir, "maps/surv_${cell(lat, lon)}.json")
            if (!force && cache.exists() && System.currentTimeMillis() - cache.lastModified() < DAY) {
                return@runCatching SurveillanceMap.parseOverpass(cache.readText())
            }
            val body = SurveillanceMap.query(lat - pad, lon - pad, lat + pad, lon + pad)
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "RadiusRF/1.3")
            }
            conn.outputStream.use { it.write("data=${java.net.URLEncoder.encode(body, "UTF-8")}".toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            cache.parentFile?.mkdirs()
            cache.parentFile?.listFiles()?.filter { it.name.startsWith("surv_") && it.name != cache.name }?.forEach { it.delete() }
            cache.writeText(text)
            SurveillanceMap.parseOverpass(text)
        }
    }

    private fun cell(lat: Double, lon: Double) = "${(lat * 20).toInt()}_${(lon * 20).toInt()}"

    companion object {
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
