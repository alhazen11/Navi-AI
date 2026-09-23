package com.apps.naviai.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.apps.naviai.memory.ConversationMemoryDao
import com.apps.naviai.memory.ConversationMemoryEntity

@Database(
    entities = [RouteEntity::class, RoutePointEntity::class, ConversationMemoryEntity::class],
    // Bumped from 1 (route recording/navigation only) to 2 for the added
    // conversation-memory table -- see DatabaseModule's
    // fallbackToDestructiveMigration(): fine during development (nothing
    // has shipped/needs its data preserved across this bump yet), but a
    // real Migration would be needed before a release build ever reaches
    // a device with an existing v1 database.
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NaviRoomDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun memoryDao(): ConversationMemoryDao
}
