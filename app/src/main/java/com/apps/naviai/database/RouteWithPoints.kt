package com.apps.naviai.database

import androidx.room.Embedded
import androidx.room.Relation

/** A [RouteEntity] joined with all of its [RoutePointEntity] rows (one route has many points). */
data class RouteWithPoints(
    @Embedded val route: RouteEntity,
    @Relation(parentColumn = "id", entityColumn = "routeId")
    val points: List<RoutePointEntity>
)
