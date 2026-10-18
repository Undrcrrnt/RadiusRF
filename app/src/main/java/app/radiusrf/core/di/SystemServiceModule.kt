package app.radiusrf.core.di

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.nfc.NfcAdapter
import android.telephony.TelephonyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SystemServiceModule {

    @Provides
    @Singleton
    fun provideSensorManager(@ApplicationContext context: Context): SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Provides
    @Singleton
    fun provideWifiManager(@ApplicationContext context: Context): WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Provides
    @Singleton
    fun provideBluetoothManager(@ApplicationContext context: Context): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Provides
    @Singleton
    fun provideUsbManager(@ApplicationContext context: Context): UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    @Provides
    @Singleton
    fun provideTelephonyManager(@ApplicationContext context: Context): TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Provides
    @Singleton
    fun provideNfcAdapter(@ApplicationContext context: Context): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(context)

    @Provides
    @Singleton
    fun provideConsumerIrManager(@ApplicationContext context: Context): ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    @Provides
    @Singleton
    fun provideWifiAwareManager(@ApplicationContext context: Context): WifiAwareManager? =
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
}
