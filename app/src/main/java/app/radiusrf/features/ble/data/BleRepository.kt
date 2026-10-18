package app.radiusrf.features.ble.data

import app.radiusrf.core.database.dao.BleDeviceDao
import app.radiusrf.core.database.entity.BleDeviceEntity
import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.ble.domain.BleDeviceSource
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.bluetooth_classic.domain.BluetoothClassicScanner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BleRepository(
    private val bleScanner: BleScanner,
    private val classicScanner: BluetoothClassicScanner,
    private val bleDeviceDao: BleDeviceDao
) {

    // Mirrors what the system Bluetooth screen does: BLE advertisement scanning surfaces live
    // passive broadcasts, Classic discovery actively requests names via inquiry — devices that
    // only speak Classic Bluetooth (many speakers/headsets/car kits) never show up in a BLE-only
    // scan at all, so both run together and merge into one address-keyed result set.
    //
    // Scan results are freshly rebuilt from the radio on every callback, so isBookmarked can't be
    // set once and forgotten — it's re-applied here from the DB on every emission, and combine()
    // re-runs this immediately when a bookmark toggle changes the DB, not just on the next scan tick.
    fun scan(): Flow<List<BleDevice>> = rawScan().combine(bleDeviceDao.getBookmarkedDevices()) { devices, bookmarked ->
        val bookmarkedAddresses = bookmarked.map { it.address }.toSet()
        devices.map { it.copy(isBookmarked = it.address in bookmarkedAddresses) }
    }

    private fun rawScan(): Flow<List<BleDevice>> = callbackFlow {
        val merged = mutableMapOf<String, BleDevice>()

        val bleJob = launch {
            bleScanner.scan().collect { devices ->
                devices.forEach { merged[it.address] = it.copy(source = BleDeviceSource.BLE) }
                trySend(merged.values.sortedByDescending { it.rssi })
            }
        }

        val classicJob = launch {
            classicScanner.discover().collect { devices ->
                devices.forEach { classicDevice ->
                    merged[classicDevice.address] = BleDevice(
                        name = classicDevice.name,
                        address = classicDevice.address,
                        rssi = classicDevice.rssi,
                        source = BleDeviceSource.CLASSIC
                    )
                }
                trySend(merged.values.sortedByDescending { it.rssi })
            }
        }

        awaitClose {
            bleJob.cancel()
            classicJob.cancel()
        }
    }

    val isAvailable: Boolean get() = bleScanner.isAvailable

    fun getBookmarkedDevices(): Flow<List<BleDevice>> {
        return bleDeviceDao.getBookmarkedDevices().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun toggleBookmark(device: BleDevice) {
        val current = bleDeviceDao.isBookmarked(device.address) ?: false
        val entity = BleDeviceEntity(
            address = device.address,
            name = device.name,
            rssi = device.rssi,
            serviceUuids = device.serviceUuids.joinToString(","),
            isBookmarked = !current,
            lastSeen = device.lastSeen
        )
        bleDeviceDao.upsert(entity)
    }

    private fun BleDeviceEntity.toDomain() = BleDevice(
        name = name,
        address = address,
        rssi = rssi,
        serviceUuids = serviceUuids?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        isBookmarked = isBookmarked,
        lastSeen = lastSeen
    )
}
