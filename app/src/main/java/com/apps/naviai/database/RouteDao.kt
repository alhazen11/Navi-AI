package com.apps.naviai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Insert
    suspend fun insertRoute(route: RouteEntity): Long

    @Insert
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("SELECT * FROM routes ORDER BY createdAt DESC")
    fun observeRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findRouteByName(name: String): RouteEntity?

    @Transaction
    @Query("SELECT * FROM routes WHERE id = :routeId")
    suspend fun getRouteWithPoints(routeId: Long): RouteWithPoints?

    @Transaction
    @Query("SELECT * FROM routes WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getRouteWithPointsByName(name: String): RouteWithPoints?

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: Long)

    @Query("UPDATE routes SET name = :newName WHERE id = :routeId")
    suspend fun renameRoute(routeId: Long, newName: String)
}
