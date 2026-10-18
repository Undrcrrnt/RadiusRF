package app.radiusrf.features.wardriving.data

import app.radiusrf.core.database.dao.WardrivingDao
import app.radiusrf.core.database.entity.WardrivingRecordEntity
import app.radiusrf.features.wardriving.domain.ExportFormat
import app.radiusrf.features.wardriving.domain.SurveyExport
import app.radiusrf.features.wardriving.domain.WardrivingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WardrivingRepository(
    private val dao: WardrivingDao
) {
    suspend fun saveRecords(sessionId: String, records: List<WardrivingRecord>) {
        records.forEach { record ->
            dao.insert(
                WardrivingRecordEntity(
                    sessionId = sessionId,
                    bssid = record.bssid,
                    ssid = record.ssid,
                    rssi = record.rssi,
                    frequency = record.frequency,
                    capabilities = record.capabilities,
                    lat = record.lat,
                    lng = record.lng,
                    timestamp = record.timestamp,
                    radio = record.radio,
                    altitude = record.altitude,
                    accuracy = record.accuracy
                )
            )
        }
    }

    fun getSessionRecords(sessionId: String): Flow<List<WardrivingRecord>> {
        return dao.getRecordsBySession(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun recentFixes(limit: Int = 400): Flow<List<WardrivingRecord>> =
        dao.recentFixes(limit).map { entities -> entities.map { it.toDomain() } }

    suspend fun exportSession(sessionId: String, format: ExportFormat): ByteArray {
        val records = dao.getRecordsBySessionList(sessionId).map { it.toDomain() }
        return SurveyExport.bytes(records, format)
    }

    private fun WardrivingRecordEntity.toDomain() = WardrivingRecord(
        bssid = bssid,
        ssid = ssid,
        rssi = rssi,
        frequency = frequency,
        capabilities = capabilities,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        radio = radio,
        altitude = altitude,
        accuracy = accuracy
    )
}
