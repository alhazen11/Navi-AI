package com.apps.naviai.database

import androidx.room.TypeConverter
import com.apps.naviai.memory.MemoryCategory

/** Room can't persist an enum column directly -- stores [MemoryCategory] as its name. */
class Converters {
    @TypeConverter
    fun fromMemoryCategory(category: MemoryCategory): String = category.name

    @TypeConverter
    fun toMemoryCategory(value: String): MemoryCategory =
        runCatching { MemoryCategory.valueOf(value) }.getOrDefault(MemoryCategory.GENERAL_MEMORY)
}
