package app.radiusrf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.radiusrf.core.database.entity.WardrivingRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WardrivingDao {

    @Query("SELECT * FROM wardriving_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getRecordsBySession(sessionId: String): Flow<List<WardrivingRecordEntity>>

    @Query("SELECT * FROM wardriving_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<WardrivingRecordEntity>>

    @Query("SELECT * FROM wardriving_records WHERE lat != 0 OR lng != 0 ORDER BY timestamp DESC LIMIT :limit")
    fun recentFixes(limit: Int): Flow<List<WardrivingRecordEntity>>

    @Insert
    suspend fun insert(record: WardrivingRecordEntity)

    @Insert
    suspend fun insertAll(records: List<WardrivingRecordEntity>)

    @Query("SELECT * FROM wardriving_records WHERE sessionId = :sessionId")
    suspend fun getRecordsBySessionList(sessionId: String): List<WardrivingRecordEntity>

    @Query("DELETE FROM wardriving_records WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM wardriving_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        """
        SELECT sessionId, MIN(timestamp) AS started, MAX(timestamp) AS ended, COUNT(*) AS sightings
        FROM wardriving_records
        GROUP BY sessionId
        ORDER BY started DESC
        """
    )
    fun surveyRuns(): Flow<List<SurveyRunRow>>

    @Query("SELECT * FROM wardriving_records WHERE sessionId IN (:sessionIds) ORDER BY timestamp ASC")
    suspend fun recordsInSessions(sessionIds: List<String>): List<WardrivingRecordEntity>

    @Query(
        """
        SELECT * FROM wardriving_records
        WHERE sessionId = :sessionId AND id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun recordsAfter(sessionId: String, afterId: Long, limit: Int): List<WardrivingRecordEntity>

    @Query(
        """
        SELECT * FROM wardriving_records
        WHERE sessionId IN (:sessionIds) AND bssid = :bssid COLLATE NOCASE
        ORDER BY timestamp ASC
        """
    )
    suspend fun recordsForDevice(sessionIds: List<String>, bssid: String): List<WardrivingRecordEntity>
}

data class SurveyRunRow(
    val sessionId: String,
    val started: Long,
    val ended: Long,
    val sightings: Int
)
