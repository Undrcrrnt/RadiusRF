package app.radiusrf.core.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DashboardPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsPrefs

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    @DashboardPrefs
    fun provideDashboardPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("RadiusRF_dashboard", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    @SettingsPrefs
    fun provideSettingsPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("RadiusRF_settings", Context.MODE_PRIVATE)
}
