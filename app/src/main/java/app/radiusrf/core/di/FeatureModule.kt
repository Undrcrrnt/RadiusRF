package app.radiusrf.core.di

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.telephony.TelephonyManager
import app.radiusrf.core.database.AppDatabase
import app.radiusrf.features.ble.data.BleRepository
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.ble.domain.GattExplorer
import app.radiusrf.features.bluetooth_classic.domain.BluetoothClassicScanner
import app.radiusrf.features.bluetooth_classic.domain.SdpServiceDiscovery
import app.radiusrf.features.bluetooth_classic.domain.SppConnectionManager
import app.radiusrf.features.camera.data.QrRepository
import app.radiusrf.features.celltower.domain.CellTowerAnalyzer
import app.radiusrf.features.gps.domain.GpsTracker
import app.radiusrf.features.gps_spoof_detector.domain.GpsSpoofDetector
import app.radiusrf.features.hidden_camera.domain.HiddenCameraDetector
import app.radiusrf.features.ir.domain.IrTransmitter
import app.radiusrf.features.network_scanner.domain.NetworkVulnerabilityScanner
import app.radiusrf.features.nfc.data.NfcRepository
import app.radiusrf.features.nfc.domain.MifareClassicReader
import app.radiusrf.features.nfc.domain.NfcTagManager
import app.radiusrf.features.sdr.domain.SdrDetector
import app.radiusrf.features.sensors.domain.SensorDataCollector
import app.radiusrf.features.ultrasonic.domain.UltrasonicAnalyzer
import app.radiusrf.core.usb.UsbConnectionManager
import app.radiusrf.features.usb.domain.UsbDeviceManager
import app.radiusrf.features.usbcamera.domain.UsbCameraDetector
import app.radiusrf.features.uwb.domain.UwbService
import app.radiusrf.features.wardriving.data.WardrivingRepository
import app.radiusrf.features.wardriving.domain.WardrivingCollector
import app.radiusrf.features.wifi.domain.UsbWifiSession
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiScanner
import app.radiusrf.features.wifi_direct.domain.WifiDirectFileTransfer
import app.radiusrf.features.wifi_direct.domain.WifiDirectManager
import app.radiusrf.features.wifiaware.domain.WifiAwareService
import android.hardware.SensorManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Mirrors what AppContainer used to lazily construct. Providers with no scope
 * annotation are intentionally unscoped (fresh instance per injection) because
 * that's how AppContainer's ViewModel factories built them - never cached.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeatureModule {

    // Phase 1 features
    @Provides
    @Singleton
    fun provideSensorDataCollector(sensorManager: SensorManager): SensorDataCollector =
        SensorDataCollector(sensorManager)

    @Provides
    @Singleton
    fun provideWifiScanner(
        @ApplicationContext context: Context,
        wifiManager: WifiManager,
        nic: WifiNicPreference,
        usb: UsbWifiSession
    ): WifiScanner = WifiScanner(context, wifiManager, nic, usb)

    @Provides
    @Singleton
    fun provideBleScanner(bluetoothManager: BluetoothManager?): BleScanner =
        BleScanner(bluetoothManager)

    @Provides
    @Singleton
    fun provideBleRepository(
        bleScanner: BleScanner,
        classicScanner: BluetoothClassicScanner,
        database: AppDatabase
    ): BleRepository = BleRepository(bleScanner, classicScanner, database.bleDeviceDao())

    // Phase 2 features
    @Provides
    @Singleton
    fun provideUsbDeviceManager(@ApplicationContext context: Context, usbManager: UsbManager?): UsbDeviceManager =
        UsbDeviceManager(context, usbManager)

    @Provides
    @Singleton
    fun provideUsbConnectionManager(@ApplicationContext context: Context, usbManager: UsbManager?): UsbConnectionManager =
        UsbConnectionManager(context, usbManager)

    @Provides
    @Singleton
    fun provideCellTowerAnalyzer(telephonyManager: TelephonyManager?): CellTowerAnalyzer =
        CellTowerAnalyzer(telephonyManager)

    @Provides
    @Singleton
    fun provideIrTransmitter(irManager: ConsumerIrManager?): IrTransmitter =
        IrTransmitter(irManager)

    @Provides
    @Singleton
    fun provideNfcTagManager(): NfcTagManager = NfcTagManager()

    @Provides
    @Singleton
    fun provideNfcRepository(nfcTagManager: NfcTagManager, database: AppDatabase): NfcRepository =
        NfcRepository(nfcTagManager, database.nfcTagDao())

    @Provides
    @Singleton
    fun provideSdrDetector(usbManager: UsbManager?): SdrDetector = SdrDetector(usbManager)

    @Provides
    @Singleton
    fun provideUltrasonicAnalyzer(): UltrasonicAnalyzer = UltrasonicAnalyzer()

    @Provides
    @Singleton
    fun provideWifiAwareService(
        @ApplicationContext context: Context,
        wifiAwareManager: WifiAwareManager?
    ): WifiAwareService = WifiAwareService(context, wifiAwareManager)

    @Provides
    @Singleton
    fun provideQrRepository(database: AppDatabase): QrRepository =
        QrRepository(database.qrScanResultDao())

    @Provides
    @Singleton
    fun provideWardrivingCollector(
        @ApplicationContext context: Context,
        wifiManager: WifiManager,
        bluetoothManager: BluetoothManager?,
        cellTowerAnalyzer: CellTowerAnalyzer
    ): WardrivingCollector = WardrivingCollector(context, wifiManager, bluetoothManager, cellTowerAnalyzer)

    @Provides
    @Singleton
    fun provideWardrivingRepository(database: AppDatabase): WardrivingRepository =
        WardrivingRepository(database.wardrivingDao())

    @Provides
    @Singleton
    fun provideUwbService(@ApplicationContext context: Context): UwbService = UwbService(context)

    @Provides
    @Singleton
    fun provideUsbCameraDetector(@ApplicationContext context: Context, usbManager: UsbManager?): UsbCameraDetector =
        UsbCameraDetector(context, usbManager)

    @Provides
    @Singleton
    fun provideGpsTracker(@ApplicationContext context: Context): GpsTracker = GpsTracker(context)

    @Provides
    @Singleton
    fun provideWifiDirectManager(@ApplicationContext context: Context): WifiDirectManager =
        WifiDirectManager(context)

    // Phase 3 features
    @Provides
    @Singleton
    fun provideBluetoothClassicScanner(
        @ApplicationContext context: Context,
        bluetoothManager: BluetoothManager?
    ): BluetoothClassicScanner = BluetoothClassicScanner(context, bluetoothManager)

    @Provides
    @Singleton
    fun provideSppConnectionManager(bluetoothManager: BluetoothManager?): SppConnectionManager =
        SppConnectionManager(bluetoothManager)

    @Provides
    @Singleton
    fun provideMifareClassicReader(): MifareClassicReader = MifareClassicReader()

    @Provides
    @Singleton
    fun provideSdpServiceDiscovery(
        @ApplicationContext context: Context,
        bluetoothManager: BluetoothManager?
    ): SdpServiceDiscovery = SdpServiceDiscovery(context, bluetoothManager)

    @Provides
    @Singleton
    fun provideWifiDirectFileTransfer(@ApplicationContext context: Context): WifiDirectFileTransfer =
        WifiDirectFileTransfer(context)

    // Phase 4 features
    @Provides
    @Singleton
    fun provideHiddenCameraDetector(@ApplicationContext context: Context): HiddenCameraDetector =
        HiddenCameraDetector(context)

    // Previously constructed fresh inside a ViewModel Factory (never cached on
    // AppContainer) - left unscoped here so each injection gets a new instance.
    @Provides
    fun provideGpsSpoofDetector(@ApplicationContext context: Context): GpsSpoofDetector =
        GpsSpoofDetector(context)

    @Provides
    fun provideNetworkVulnerabilityScanner(
        wifiScanner: WifiScanner,
        @ApplicationContext context: Context
    ): NetworkVulnerabilityScanner = NetworkVulnerabilityScanner(wifiScanner, context)

    @Provides
    fun provideGattExplorer(
        @ApplicationContext context: Context,
        bluetoothManager: BluetoothManager?
    ): GattExplorer = GattExplorer(context, bluetoothManager)
}
