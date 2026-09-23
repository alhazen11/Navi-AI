package com.apps.naviai.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Unique (case-insensitive via a COLLATE NOCASE column) so two routes can
// never end up sharing a name -- RouteDao's name-based lookups
// (findRouteByName/getRouteWithPointsByName, used by voice "navigate to X"
// and "rename route X") do a COLLATE NOCASE `LIMIT 1` with no ORDER BY, so
// without this constraint a second same-named route silently made those
// lookups resolve to an arbitrary one of the two. See
// [RouteRepositoryImpl.saveRoute] for how a collision is avoided before it
// ever reaches this constraint.
@Entity(tableName = "routes", indices = [Index(value = ["name"], unique = true)])
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // NOCASE to match the COLLATE NOCASE comparisons RouteDao's name-based
    // queries already use -- otherwise the unique index above would still
    // be case-sensitive and let "Kantor" and "kantor" coexist as two "same"
    // routes by every lookup's own definition of "same".
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
    val totalDistanceMeters: Double,
    val totalPoints: Int
)
