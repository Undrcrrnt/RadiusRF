package app.radiusrf.features.run_compare.domain

import app.radiusrf.features.wardriving.domain.SurveyRadio
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoFix(val lat: Double, val lng: Double)

/** Thinned GPS path for one saved run. */
data class RunTrack(val sessionId: String, val points: List<GeoFix>)

/** One saved wardriving session, ready to be checked for comparison. */
data class SurveyRun(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val sightings: Int
)

/** One logged observation. [mac] keeps the text that was stored. */
data class CompareSighting(
    val sessionId: String,
    val mac: String,
    val name: String?,
    val radio: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String?,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val altitude: Double,
    val accuracy: Double
) {
    val hasFix: Boolean get() = lat != 0.0 || lng != 0.0
}

/**
 * A device heard on more than one run, far enough apart that it traveled
 * with the route instead of staying on a building.
 */
data class DeviceMatch(
    val mac: String,
    val displayMac: String,
    val label: String,
    val radio: String,
    val runIds: List<String>,
    val sightings: List<CompareSighting>,
    val spanMeters: Double,
    val count: Int = sightings.size,
    val path: List<GeoFix> = emptyList()
)

object RunCompare {
    const val MIN_RUNS = 2
    const val MAX_RUNS = 4

    /** Fixes that all sit inside this distance are a place, not a traveler. */
    const val MOVED_METERS = 150.0

    const val TRACK_STEP_METERS = 20.0
    private val MOBILE = setOf(SurveyRadio.WIFI, SurveyRadio.BLE, SurveyRadio.BT, SurveyRadio.CLIENT)
    private val IGNORED = setOf(
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
        "ff:ff:ff:ff:ff:ff"
    )

    fun accepts(sighting: CompareSighting): Boolean {
        val mac = sighting.mac.trim().lowercase()
        return mac.isNotBlank() && mac !in IGNORED && sighting.radio in MOBILE
    }

    fun matches(sightings: List<CompareSighting>, movedMeters: Double = MOVED_METERS): List<DeviceMatch> {
        return sightings
            .groupBy { it.mac.trim().lowercase() }
            .mapNotNull { (mac, group) ->
                if (mac.isBlank() || mac in IGNORED) return@mapNotNull null
                if (group.none { it.radio in MOBILE }) return@mapNotNull null
                val mobile = group.filter { it.radio in MOBILE }
                val runs = mobile.map { it.sessionId }.distinct()
                if (runs.size < MIN_RUNS) return@mapNotNull null
                val fixes = mobile.filter { it.hasFix }
                if (fixes.size < 2) return@mapNotNull null
                val span = spanMeters(fixes)
                if (span < movedMeters) return@mapNotNull null
                val latest = mobile.maxBy { it.timestamp }
                val named = mobile.filter { !it.name.isNullOrBlank() }.maxByOrNull { it.timestamp }?.name
                DeviceMatch(
                    mac = mac,
                    displayMac = latest.mac,
                    label = named?.takeIf { it.isNotBlank() } ?: latest.mac,
                    radio = latest.radio,
                    runIds = runs,
                    sightings = mobile.sortedBy { it.timestamp },
                    spanMeters = span
                )
            }
            .sortedWith(compareByDescending<DeviceMatch> { it.runIds.size }.thenByDescending { it.spanMeters })
    }

    /** Route polyline, dropping fixes that barely moved so the map stays readable. */
    fun track(sightings: List<CompareSighting>, minStepMeters: Double = TRACK_STEP_METERS): List<GeoFix> {
        val sorted = sightings.filter { it.hasFix }.sortedBy { it.timestamp }
        val out = ArrayList<GeoFix>(sorted.size)
        var last: GeoFix? = null
        for (sighting in sorted) {
            val fix = GeoFix(sighting.lat, sighting.lng)
            val previous = last
            if (previous == null || meters(previous.lat, previous.lng, fix.lat, fix.lng) >= minStepMeters) {
                out += fix
                last = fix
            }
        }
        return out
    }

    fun spanMeters(sightings: List<CompareSighting>): Double {
        val fixes = sightings.filter { it.hasFix }
        if (fixes.size < 2) return 0.0
        val minLat = fixes.minOf { it.lat }
        val maxLat = fixes.maxOf { it.lat }
        val minLng = fixes.minOf { it.lng }
        val maxLng = fixes.maxOf { it.lng }
        return meters(minLat, minLng, maxLat, maxLng)
    }

    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earth = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * earth * asin(sqrt(a))
    }
}

/**
 * Running totals for one address while a log is read a page at a time.
 * [qualifies] uses the same rule as [RunCompare.matches].
 */
class MacStats {
    private val sessions = LinkedHashSet<String>()
    private var fixes = 0
    private var minLat = Double.POSITIVE_INFINITY
    private var maxLat = Double.NEGATIVE_INFINITY
    private var minLng = Double.POSITIVE_INFINITY
    private var maxLng = Double.NEGATIVE_INFINITY
    private var latestTime = Long.MIN_VALUE
    private var namedTime = Long.MIN_VALUE
    private var fixTime = Long.MIN_VALUE
    var displayMac: String = ""
        private set
    var label: String = ""
        private set
    var radio: String = ""
        private set
    var count: Int = 0
        private set
    var latest: GeoFix? = null
        private set

    fun add(sighting: CompareSighting) {
        if (!RunCompare.accepts(sighting)) return
        count++
        sessions.add(sighting.sessionId)
        if (sighting.timestamp >= latestTime) {
            latestTime = sighting.timestamp
            displayMac = sighting.mac
            radio = sighting.radio
            if (label.isBlank()) label = sighting.mac
        }
        val name = sighting.name
        if (!name.isNullOrBlank() && sighting.timestamp >= namedTime) {
            namedTime = sighting.timestamp
            label = name
        }
        if (!sighting.hasFix || !sighting.lat.isFinite() || !sighting.lng.isFinite()) return
        fixes++
        if (sighting.lat < minLat) minLat = sighting.lat
        if (sighting.lat > maxLat) maxLat = sighting.lat
        if (sighting.lng < minLng) minLng = sighting.lng
        if (sighting.lng > maxLng) maxLng = sighting.lng
        if (sighting.timestamp >= fixTime) {
            fixTime = sighting.timestamp
            latest = GeoFix(sighting.lat, sighting.lng)
        }
    }

    fun qualifies(movedMeters: Double = RunCompare.MOVED_METERS): Boolean {
        if (sessions.size < RunCompare.MIN_RUNS || fixes < 2) return false
        return span() >= movedMeters
    }

    fun span(): Double {
        if (fixes < 2) return 0.0
        return RunCompare.meters(minLat, minLng, maxLat, maxLng)
    }

    fun sessionsIn(order: List<String>): List<String> = order.filter { it in sessions }
}
