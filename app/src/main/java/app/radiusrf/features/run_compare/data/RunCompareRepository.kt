package app.radiusrf.features.run_compare.data

import app.radiusrf.core.database.dao.WardrivingDao
import app.radiusrf.core.database.entity.WardrivingRecordEntity
import app.radiusrf.features.run_compare.domain.CompareSighting
import app.radiusrf.features.run_compare.domain.DeviceMatch
import app.radiusrf.features.run_compare.domain.GeoFix
import app.radiusrf.features.run_compare.domain.MacStats
import app.radiusrf.features.run_compare.domain.RunCompare
import app.radiusrf.features.run_compare.domain.RunTrack
import app.radiusrf.features.run_compare.domain.SurveyRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PreparedComparison(
    val matches: List<DeviceMatch>,
    val tracks: List<RunTrack>
)

class RunCompareRepository(
    private val dao: WardrivingDao
) {
    fun runs(): Flow<List<SurveyRun>> = dao.surveyRuns().map { rows ->
        rows.map { row ->
            SurveyRun(
                id = row.sessionId,
                startedAt = row.started,
                endedAt = row.ended,
                sightings = row.sightings
            )
        }
    }

    suspend fun delete(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    /**
     * Reads each selected run in short pages. Counters stay, and only a match's
     * own rows are loaded again when that device is opened.
     */
    suspend fun comparison(sessionIds: List<String>): PreparedComparison {
        val order = sessionIds.distinct()
        if (order.isEmpty()) return PreparedComparison(emptyList(), emptyList())
        val stats = HashMap<String, MacStats>()
        val tracks = LinkedHashMap<String, MutableList<GeoFix>>()
        val lastFix = HashMap<String, GeoFix>()
        for (id in order) {
            var after = -1L
            while (true) {
                val page = dao.recordsAfter(id, after, PAGE)
                if (page.isEmpty()) break
                for (row in page) {
                    val sighting = row.toSighting()
                    noteTrack(tracks, lastFix, sighting)
                    if (!RunCompare.accepts(sighting)) continue
                    val key = sighting.mac.trim().lowercase()
                    stats.getOrPut(key) { MacStats() }.add(sighting)
                }
                after = page.last().id
                if (page.size < PAGE) break
            }
        }
        val matches = stats.mapNotNull { (mac, item) ->
            if (!item.qualifies()) return@mapNotNull null
            DeviceMatch(
                mac = mac,
                displayMac = item.displayMac,
                label = item.label.ifBlank { item.displayMac },
                radio = item.radio,
                runIds = item.sessionsIn(order),
                sightings = emptyList(),
                spanMeters = item.span(),
                count = item.count,
                path = listOfNotNull(item.latest)
            )
        }.sortedWith(compareByDescending<DeviceMatch> { it.runIds.size }.thenByDescending { it.spanMeters })
        return PreparedComparison(
            matches = matches,
            tracks = order.map { id -> RunTrack(id, tracks[id].orEmpty()) }
        )
    }

    suspend fun deviceSightings(sessionIds: List<String>, mac: String): List<CompareSighting> {
        val ids = sessionIds.distinct()
        if (ids.isEmpty() || mac.isBlank()) return emptyList()
        return dao.recordsForDevice(ids, mac.trim()).map { it.toSighting() }
    }

    private fun noteTrack(
        tracks: MutableMap<String, MutableList<GeoFix>>,
        lastFix: MutableMap<String, GeoFix>,
        sighting: CompareSighting
    ) {
        if (!sighting.hasFix || !sighting.lat.isFinite() || !sighting.lng.isFinite()) return
        val fix = GeoFix(sighting.lat, sighting.lng)
        val previous = lastFix[sighting.sessionId]
        if (previous != null &&
            RunCompare.meters(previous.lat, previous.lng, fix.lat, fix.lng) < RunCompare.TRACK_STEP_METERS
        ) {
            return
        }
        tracks.getOrPut(sighting.sessionId) { ArrayList() }.add(fix)
        lastFix[sighting.sessionId] = fix
    }

    private fun WardrivingRecordEntity.toSighting() = CompareSighting(
        sessionId = sessionId,
        mac = bssid,
        name = ssid,
        radio = radio,
        rssi = rssi,
        frequency = frequency,
        capabilities = capabilities,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        altitude = altitude,
        accuracy = accuracy
    )

    private companion object {
        const val PAGE = 400
    }
}
