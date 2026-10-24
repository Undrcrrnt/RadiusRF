package app.radiusrf.features.camera.data

import app.radiusrf.core.database.dao.QrScanResultDao
import app.radiusrf.core.database.entity.QrScanResultEntity
import app.radiusrf.features.camera.domain.QrContentType
import app.radiusrf.features.camera.domain.QrScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class QrRepository(
    private val dao: QrScanResultDao
) {
    fun getRecentScans(limit: Int = 50): Flow<List<QrScanResult>> {
        return dao.getRecentResults(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun saveScan(result: QrScanResult) {
        dao.insert(
            QrScanResultEntity(
                rawValue = result.rawValue,
                format = result.format,
                contentType = result.contentType.name,
                parsedContent = result.parsedContent,
                isThreat = result.isThreat,
                threatReason = result.threatReason,
                timestamp = result.timestamp
            )
        )
    }

    suspend fun clearHistory() {
        dao.deleteAll()
    }

    private fun QrScanResultEntity.toDomain() = QrScanResult(
        rawValue = rawValue,
        format = format,
        contentType = try { QrContentType.valueOf(contentType) } catch (_: Exception) { QrContentType.UNKNOWN },
        parsedContent = parsedContent ?: rawValue,
        isThreat = isThreat,
        threatReason = threatReason,
        timestamp = timestamp
    )
}
