package app.radiusrf.features.wifi.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.max

/** One measured spot on a floor walk. x and z are metres from where the walk started. */
data class FloorSample(
    val timeMs: Long,
    val x: Double,
    val z: Double,
    val headingDeg: Float,
    val rssi: Int,
    val bssid: String?,
    val frequencyMhz: Int?,
    val roam: Boolean = false
)

/** A finished walk kept on the phone. */
data class FloorWalkRecord(
    val id: String,
    val startedAt: Long,
    val ssid: String,
    val samples: List<FloorSample>
)

/** Words printed on the shared map image. */
data class FloorWalkSheet(
    val title: String,
    val detail: String,
    val scaleLabel: String,
    val note: String,
    val roams: List<String>
)

/** Camera pose already shifted so the start of the walk is (0, 0). */
data class FloorPose(
    val x: Double,
    val z: Double,
    val headingDeg: Float,
    val tracking: Boolean
)

/**
 * Rules for the floor walk. The walk records the network this phone is joined to.
 * It does not decide when Wi-Fi scans run.
 */
object FloorWalkMath {
    const val WEAK_DBM = -100
    const val STRONG_DBM = -30
    const val MIN_MOVE_M = 0.20
    const val SLOW_MOVE_M = 0.05
    const val SAMPLE_INTERVAL_MS = 1_000L
    const val RSSI_STEP_DB = 5
    const val MAX_SAMPLES = 5_000

    fun usableRssi(rssi: Int): Boolean = rssi in (WEAK_DBM + 1)..-1

    /** 0 is the weak end of the path color, 1 is the strong end. */
    fun strength(rssi: Int): Float {
        val span = (STRONG_DBM - WEAK_DBM).toFloat()
        return ((rssi - WEAK_DBM).toFloat() / span).coerceIn(0f, 1f)
    }

    fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (trimmed.isBlank()) return null
        if (trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        return trimmed
    }

    fun bandName(mhz: Int?): String = when (mhz) {
        null -> ""
        in 2400..2500 -> "2.4 GHz"
        in 4900..5899 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "$mhz MHz"
    }

    fun yawDegrees(qx: Float, qy: Float, qz: Float, qw: Float): Float {
        val siny = 2.0 * (qw * qy + qx * qz)
        val cosy = 1.0 - 2.0 * (qy * qy + qz * qz)
        return Math.toDegrees(atan2(siny, cosy)).toFloat()
    }

    fun shouldStore(last: FloorSample?, x: Double, z: Double, rssi: Int, timeMs: Long): Boolean {
        if (!x.isFinite() || !z.isFinite() || !usableRssi(rssi)) return false
        if (last == null) return true
        val dx = x - last.x
        val dz = z - last.z
        val moved = dx * dx + dz * dz
        if (moved >= MIN_MOVE_M * MIN_MOVE_M) return true
        val waited = timeMs - last.timeMs >= SAMPLE_INTERVAL_MS
        if (waited && moved >= SLOW_MOVE_M * SLOW_MOVE_M) return true
        if (waited && kotlin.math.abs(rssi - last.rssi) >= RSSI_STEP_DB) return true
        return false
    }

    fun markRoam(previous: FloorSample?, sample: FloorSample): FloorSample {
        val previousBssid = previous?.bssid
        val nextBssid = sample.bssid
        val changed = previousBssid != null && nextBssid != null &&
            !previousBssid.equals(nextBssid, ignoreCase = true)
        return sample.copy(roam = changed)
    }

    /** A round length for the scale bar, in metres, for a path of this span. */
    fun scaleBarMetres(spanMetres: Double): Double = when {
        spanMetres < 8.0 -> 1.0
        spanMetres < 20.0 -> 2.0
        spanMetres < 50.0 -> 5.0
        else -> 10.0
    }

    const val SHEET_NOTE = "Blank space was not walked. The scale is an estimate and drifts."

    /** Title block for a PNG of this walk. [whenText] is the formatted start time. */
    fun sheet(record: FloorWalkRecord, whenText: String): FloorWalkSheet {
        val bands = record.samples.mapNotNull { sample ->
            bandName(sample.frequencyMhz).takeIf { it.isNotBlank() }
        }.distinct()
        val detail = listOfNotNull(
            whenText.takeIf { it.isNotBlank() },
            "${record.samples.size} points",
            bands.joinToString(", ").takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        val roams = record.samples
            .filter { it.roam && !it.bssid.isNullOrBlank() }
            .take(12)
            .map { "${it.bssid} · ${it.rssi} dBm" }
        return FloorWalkSheet(
            title = record.ssid.ifBlank { "Joined network" },
            detail = detail,
            scaleLabel = "${scaleBarMetres(spanMetres(record.samples)).toInt()} m, estimated",
            note = SHEET_NOTE,
            roams = roams
        )
    }

    fun spanMetres(samples: List<FloorSample>): Double {
        if (samples.isEmpty()) return 4.0
        val spanX = samples.maxOf { it.x } - samples.minOf { it.x }
        val spanZ = samples.maxOf { it.z } - samples.minOf { it.z }
        return max(spanX, spanZ).coerceAtLeast(4.0)
    }

    fun encode(walk: FloorWalkRecord): String {
        val samples = JSONArray()
        walk.samples.forEach { sample ->
            val point = JSONObject()
                .put("t", sample.timeMs)
                .put("x", sample.x)
                .put("z", sample.z)
                .put("h", sample.headingDeg.toDouble())
                .put("rssi", sample.rssi)
                .put("roam", sample.roam)
            if (sample.bssid != null) point.put("bssid", sample.bssid)
            if (sample.frequencyMhz != null) point.put("mhz", sample.frequencyMhz)
            samples.put(point)
        }
        return JSONObject()
            .put("id", walk.id)
            .put("startedAt", walk.startedAt)
            .put("ssid", walk.ssid)
            .put("samples", samples)
            .toString()
    }

    fun decode(json: String): FloorWalkRecord? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = root.optString("id")
        if (id.isBlank()) return null
        val points = root.optJSONArray("samples") ?: JSONArray()
        val samples = buildList {
            for (i in 0 until points.length()) {
                val point = points.optJSONObject(i) ?: continue
                val x = point.optDouble("x")
                val z = point.optDouble("z")
                val rssi = point.optInt("rssi", Int.MIN_VALUE)
                if (!x.isFinite() || !z.isFinite() || !usableRssi(rssi)) continue
                add(
                    FloorSample(
                        timeMs = point.optLong("t"),
                        x = x,
                        z = z,
                        headingDeg = point.optDouble("h").toFloat(),
                        rssi = rssi,
                        bssid = point.optString("bssid").takeIf { point.has("bssid") && it.isNotBlank() },
                        frequencyMhz = point.optInt("mhz").takeIf { point.has("mhz") && it > 0 },
                        roam = point.optBoolean("roam")
                    )
                )
            }
        }
        if (samples.isEmpty()) return null
        val ssid = cleanSsid(root.optString("ssid")) ?: "Joined network"
        return FloorWalkRecord(
            id = id,
            startedAt = root.optLong("startedAt"),
            ssid = ssid,
            samples = samples
        )
    }
}
