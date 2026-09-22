package com.apps.naviai.di

import com.apps.naviai.core.common.IoDispatcher
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.core.common.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @InferenceDispatcher
    fun provideInferenceDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "naviai-inference")
        }.asCoroutineDispatcher()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
