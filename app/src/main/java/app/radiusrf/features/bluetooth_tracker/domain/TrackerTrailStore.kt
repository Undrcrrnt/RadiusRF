package app.radiusrf.features.bluetooth_tracker.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class TrailPoint(val lat: Double, val lon: Double, val timeMs: Long)

data class TrackerTrail(
    val identity: String,
    val label: String,
    val owner: OwnerState,
    val points: List<TrailPoint>
)

/**
 * Places a tracker was heard, kept on the phone. A new point is stored when it is at least
 * 250 m from the previous one, which is the same spacing the live scanner uses.
 */
@Singleton
class TrackerTrailStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "tracker-trails.json")
    private val trails = LinkedHashMap<String, TrackerTrail>()

    init {
        runCatching { read(file.readText()) }.getOrNull()?.let { saved ->
            saved.forEach { trails[it.identity] = it }
        }
    }

    fun all(): List<TrackerTrail> = synchronized(trails) { trails.values.toList() }

    fun note(identity: String, label: String, owner: OwnerState, lat: Double, lon: Double, timeMs: Long) {
        synchronized(trails) {
            val current = trails[identity]
            val points = current?.points.orEmpty()
            val last = points.lastOrNull()
            val next = if (last == null || meters(last.lat, last.lon, lat, lon) >= 250.0) {
                (points + TrailPoint(lat, lon, timeMs)).takeLast(32)
            } else {
                points
            }
            trails[identity] = TrackerTrail(identity, label, owner, next)
            while (trails.size > 40) {
                val oldest = trails.entries.minByOrNull { it.value.points.lastOrNull()?.timeMs ?: 0L }?.key
                if (oldest == null) break
                trails.remove(oldest)
            }
            runCatching { file.writeText(write(trails.values.toList())) }
        }
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    companion object {
        fun read(json: String): List<TrackerTrail> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val points = o.optJSONArray("points") ?: JSONArray()
                TrackerTrail(
                    identity = o.optString("identity"),
                    label = o.optString("label"),
                    owner = runCatching { OwnerState.valueOf(o.optString("owner")) }.getOrDefault(OwnerState.UNKNOWN),
                    points = (0 until points.length()).mapNotNull { p ->
                        val row = points.optJSONObject(p) ?: return@mapNotNull null
                        TrailPoint(row.optDouble("lat"), row.optDouble("lon"), row.optLong("time"))
                    }
                )
            }
        }

        fun write(trails: List<TrackerTrail>): String = JSONArray().apply {
            trails.forEach { trail ->
                put(
                    JSONObject()
                        .put("identity", trail.identity)
                        .put("label", trail.label)
                        .put("owner", trail.owner.name)
                        .put("points", JSONArray().apply {
                            trail.points.forEach { point ->
                                put(JSONObject().put("lat", point.lat).put("lon", point.lon).put("time", point.timeMs))
                            }
                        })
                )
            }
        }.toString()
    }
}
