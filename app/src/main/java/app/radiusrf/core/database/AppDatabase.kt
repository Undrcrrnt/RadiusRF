package app.radiusrf.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.radiusrf.core.database.dao.AlertDao
import app.radiusrf.core.database.dao.SessionDao
import app.radiusrf.core.database.dao.BleDeviceDao
import app.radiusrf.core.database.dao.NfcTagDao
import app.radiusrf.core.database.dao.QrScanResultDao
import app.radiusrf.core.database.dao.WardrivingDao
import app.radiusrf.core.database.entity.AlertEntity
import app.radiusrf.core.database.entity.SessionEntity
import app.radiusrf.core.database.entity.SessionItemEntity
import app.radiusrf.core.database.entity.BleDeviceEntity
import app.radiusrf.core.database.entity.NfcTagEntity
import app.radiusrf.core.database.entity.QrScanResultEntity
import app.radiusrf.core.database.entity.WardrivingRecordEntity

@Database(
    entities = [
        BleDeviceEntity::class,
        NfcTagEntity::class,
        WardrivingRecordEntity::class,
        QrScanResultEntity::class,
        AlertEntity::class,
        SessionEntity::class,
        SessionItemEntity::class
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5)
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bleDeviceDao(): BleDeviceDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun wardrivingDao(): WardrivingDao
    abstract fun qrScanResultDao(): QrScanResultDao
    abstract fun alertDao(): AlertDao
    abstract fun sessionDao(): SessionDao
}
