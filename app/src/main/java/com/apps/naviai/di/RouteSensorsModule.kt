package com.apps.naviai.di

import com.apps.naviai.compass.CompassManager
import com.apps.naviai.compass.DeviceCompassManager
import com.apps.naviai.location.FusedLocationProvider
import com.apps.naviai.location.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RouteSensorsModule {
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: FusedLocationProvider): LocationProvider

    @Binds
    @Singleton
    abstract fun bindCompassManager(impl: DeviceCompassManager): CompassManager
}
