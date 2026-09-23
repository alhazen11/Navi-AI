package com.apps.naviai.database

import androidx.room.withTransaction
import com.apps.naviai.location.GeoMath
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence for recorded routes -- see [RouteDao] for the underlying queries. */
interface RouteRepository {
    fun observeRoutes(): Flow<List<RouteEntity>>

    /**
     * Saves [points] (already in chronological/sequence order -- this
     * assigns sequenceNumber from that order, it does not sort them) as a
     * new route, computing total distance from consecutive-point deltas.
     * @return the new route's id.
     */
    suspend fun saveRoute(name: String, createdAt: Long, points: List<RoutePointEntity>): Long

    suspend fun getRouteWithPoints(routeId: Long): RouteWithPoints?
    suspend fun getRouteWithPointsByName(name: String): RouteWithPoints?
    suspend fun deleteRoute(routeId: Long)
    suspend fun renameRoute(routeId: Long, newName: String)

    /**
     * Renames the route currently named [oldName] (case-insensitive, same
     * lookup [getRouteWithPointsByName] uses) to [newName] -- for the voice
     * "rename route X to Y" command, which only ever has the spoken name to
     * go on, not an id.
     * @return true if a matching route was found and renamed, false if no
     *   route is named [oldName].
     */
    suspend fun renameRouteByName(oldName: String, newName: String): Boolean
}

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val database: NaviRoomDatabase,
    private val dao: RouteDao
) : RouteRepository {

    override fun observeRoutes(): Flow<List<RouteEntity>> = dao.observeRoutes()

    override suspend fun saveRoute(name: String, createdAt: Long, points: List<RoutePointEntity>): Long {
        // Computed before withTransaction opens: this is a plain CPU loop
        // over every recorded point with no need for the write transaction
        // to be held open while it runs, unlike the actual inserts below.
        val distance = totalDistanceMeters(points)
        val uniqueName = uniqueRouteName(name)
        return database.withTransaction {
            val routeId = dao.insertRoute(
                RouteEntity(
                    name = uniqueName,
                    createdAt = createdAt,
                    totalDistanceMeters = distance,
                    totalPoints = points.size
                )
            )
            dao.insertPoints(points.mapIndexed { index, point -> point.copy(routeId = routeId, sequenceNumber = index) })
            routeId
        }
    }

    /**
     * [RouteEntity.name] is unique (case-insensitive), so a second route
     * saved under an already-used name (an accidental repeat, or the same
     * default spoken phrase) would otherwise fail the whole save with a
     * constraint-violation exception. Instead this finds the first "name
     * (2)", "name (3)", ... suffix that isn't taken yet, so saving never
     * fails outright and every route keeps a name lookups can uniquely
     * resolve.
     */
    private suspend fun uniqueRouteName(name: String, excludingRouteId: Long? = null): String {
        suspend fun isTaken(candidate: String): Boolean {
            val existing = dao.findRouteByName(candidate) ?: return false
            return existing.id != excludingRouteId
        }
        if (!isTaken(name)) return name
        var suffix = 2
        while (isTaken("$name ($suffix)")) suffix++
        return "$name ($suffix)"
    }

    override suspend fun getRouteWithPoints(routeId: Long): RouteWithPoints? = dao.getRouteWithPoints(routeId)?.orderedBySequence()

    override suspend fun getRouteWithPointsByName(name: String): RouteWithPoints? =
        dao.getRouteWithPointsByName(name)?.orderedBySequence()

    override suspend fun deleteRoute(routeId: Long) = dao.deleteRoute(routeId)

    // uniqueRouteName(..., excludingRouteId = routeId): without excluding
    // the route's own current row, renaming "kantor" to "kantor" (a no-op
    // rename) or to a name only this same route already holds would
    // otherwise be treated as a collision against itself and get an
    // unnecessary " (2)" suffix appended.
    override suspend fun renameRoute(routeId: Long, newName: String) =
        dao.renameRoute(routeId, uniqueRouteName(newName, excludingRouteId = routeId))

    override suspend fun renameRouteByName(oldName: String, newName: String): Boolean {
        val route = dao.findRouteByName(oldName) ?: return false
        dao.renameRoute(route.id, uniqueRouteName(newName, excludingRouteId = route.id))
        return true
    }

    // Room's @Relation query doesn't guarantee row order, so this sorts
    // explicitly by the authoritative sequenceNumber rather than trusting
    // whatever order SQLite happened to return.
    private fun RouteWithPoints.orderedBySequence() = copy(points = points.sortedBy { it.sequenceNumber })

    private fun totalDistanceMeters(points: List<RoutePointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += GeoMath.distanceMeters(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
        }
        return total
    }
}
