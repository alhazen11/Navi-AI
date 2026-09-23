package com.apps.naviai.di

import android.content.Context
import androidx.room.Room
import com.apps.naviai.database.NaviRoomDatabase
import com.apps.naviai.database.RouteDao
import com.apps.naviai.database.RouteRepository
import com.apps.naviai.database.RouteRepositoryImpl
import com.apps.naviai.memory.ConversationMemoryDao
import com.apps.naviai.memory.ConversationMemoryRepository
import com.apps.naviai.memory.ConversationMemoryRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NaviRoomDatabase =
        Room.databaseBuilder(context, NaviRoomDatabase::class.java, "navi_ai_routes.db")
            // Development-time only: see NaviRoomDatabase's version-bump
            // comment. Destroys and recreates the local DB on a schema
            // mismatch instead of crashing -- fine here since nothing has
            // shipped that needs its local data preserved across this
            // exact bump; replace with real Migration objects before a
            // release build reaches a device with an existing database.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideRouteDao(database: NaviRoomDatabase): RouteDao = database.routeDao()

    @Provides
    fun provideMemoryDao(database: NaviRoomDatabase): ConversationMemoryDao = database.memoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindModule {
    @Binds
    @Singleton
    abstract fun bindRouteRepository(impl: RouteRepositoryImpl): RouteRepository

    @Binds
    @Singleton
    abstract fun bindConversationMemoryRepository(impl: ConversationMemoryRepositoryImpl): ConversationMemoryRepository
}
