package app.radiusrf.core.di

import android.content.Context
import app.radiusrf.core.hardware.HardwareChecker
import app.radiusrf.features.dashboard.DeviceInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HardwareModule {

    @Provides
    @Singleton
    fun provideHardwareChecker(@ApplicationContext context: Context): HardwareChecker =
        HardwareChecker(context)

    @Provides
    @Singleton
    fun provideDeviceInfo(): DeviceInfo = DeviceInfo.fromBuild()
}
