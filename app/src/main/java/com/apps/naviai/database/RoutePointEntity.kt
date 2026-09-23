package com.apps.naviai.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_points",
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // routeId alone speeds up "all points for this route"; the composite
    // index additionally lets that query be answered pre-sorted by Room's
    // ORDER BY sequenceNumber without a separate sort pass.
    indices = [Index("routeId"), Index(value = ["routeId", "sequenceNumber"])]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    /** Position of this point within its route, 0-based -- authoritative ordering, independent of [id] or [timestamp]. */
    val sequenceNumber: Int
)
