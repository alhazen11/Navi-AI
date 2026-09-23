package com.apps.naviai.routenav

import android.content.Context
import android.util.Log
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.audio.VoiceCommandStatus
import com.apps.naviai.database.RouteRepository
import com.apps.naviai.compass.CompassManager
import com.apps.naviai.location.LocationPermission
import com.apps.naviai.location.LocationProvider
import com.apps.naviai.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NavigationUiPhase {
    data object Idle : NavigationUiPhase
    data class Active(
        val routeName: String,
        val currentInstruction: String,
        val distanceToNextPointMeters: Double?,
        val progressIndex: Int,
        val totalPoints: Int,
        val gpsAccuracyMeters: Float?,
        val headingDegrees: Double?,
        val isDeviated: Boolean
    ) : NavigationUiPhase
    data class Finished(val routeName: String) : NavigationUiPhase
    data class Failed(val message: String) : NavigationUiPhase
}

/**
 * Owns the actual GPS+compass+[NavigationEngine] loop for Voice-Based Route
 * Navigation, independent of whether any screen/Activity is visible --
 * [com.apps.naviai.routenav.RouteNavigationService] just keeps the process
 * alive (foreground notification) while this runs, it doesn't own the
 * logic itself. Also owns voice listening for "stop navigasi" while
 * active, so that command works with the screen off/app backgrounded, not
 * only from a visible screen (unlike this app's other voice features,
 * which only listen while their own screen is on-screen).
 */
@Singleton
class NavigationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeRepository: RouteRepository,
    private val locationProvider: LocationProvider,
    private val compassManager: CompassManager,
    private val ttsManager: TextToSpeechManager,
    private val voiceCommandManager: VoiceCommandManager,
    private val settingsRepository: SettingsRepository
) {
    private val _phase = MutableStateFlow<NavigationUiPhase>(NavigationUiPhase.Idle)
    val phase: StateFlow<NavigationUiPhase> = _phase.asStateFlow()

    // Dispatchers.Main.immediate, not a bare SupervisorJob: both
    // FusedLocationProviderClient.requestLocationUpdates() and
    // SensorManager.registerListener() (the compass) need a Looper-backed
    // thread to deliver callbacks on and crash immediately without one --
    // a bare SupervisorJob defaults to Dispatchers.Default, a thread-pool
    // thread with no Looper.prepare() ever called. See the matching
    // comment in FusedLocationProvider.locationUpdates() / RouteRecorder.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var voiceJob: Job? = null
    private var lastHandledVoiceEventId: Long = 0

    @Volatile private var language = AnnouncementLanguage.INDONESIAN
    @Volatile private var apiKey: String? = null
    @Volatile private var offlineModeEnabled = false

    private var lastTurnText: String? = null
    private var lastTurnSpokenAtMs = 0L
    private var lastDistanceAnnouncedAtMs = 0L
    private var lastPoorGpsAnnouncedAtMs = 0L
    private var lastDeviationSpoken: Boolean? = null

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                language = settings.speechLanguage
                apiKey = settings.assemblyAiApiKey
                offlineModeEnabled = settings.offlineModeEnabled
            }
        }
    }

    val isActive: Boolean get() = _phase.value is NavigationUiPhase.Active

    /**
     * Idempotent while already navigating. Safe to call from any thread
     * that can start coroutines (used from the foreground service). A
     * no-op beyond speaking a message (no crash) if ACCESS_FINE_LOCATION
     * isn't granted -- see [LocationPermission]'s doc and
     * [com.apps.naviai.recording.RouteRecorder.start] for the same guard
     * on the recording side.
     */
    fun start(routeName: String) {
        // job?.isActive (not just the isActive PROPERTY above) guards
        // against a second start() call arriving before the first GPS fix
        // ever comes in -- the isActive property only flips true once
        // handleEvent's first publish() runs, so relying on it alone left a
        // window where a fast double-start (e.g. a double-tap, or the
        // service's onStartCommand firing twice) reassigned job/voiceJob
        // without cancelling the originals, leaking the first run's
        // coroutines and doubling GPS/voice/TTS activity. Checking
        // job.isActive rather than just `job != null` matters too: job is
        // never reset to null after finishing on its own (route-not-found),
        // only when stop() cancels it, so `!= null` would permanently block
        // ever starting again after a single route-not-found failure.
        if (isActive || job?.isActive == true) return
        if (!LocationPermission.isGranted(context)) {
            ttsManager.speak(NavigationAnnouncements.locationPermissionRequired(language), flushQueue = true, utteranceId = "nav_permission_required")
            return
        }
        resetAnnouncementState()

        voiceCommandManager.startContinuousListening(language, apiKey, offlineModeEnabled)
        voiceJob = scope.launch {
            voiceCommandManager.state.collect { voiceState ->
                if (voiceState.status != VoiceCommandStatus.RECOGNIZED || voiceState.eventId == lastHandledVoiceEventId) return@collect
                val command = voiceState.command ?: return@collect
                lastHandledVoiceEventId = voiceState.eventId
                if (NavigationCommandMatcher.isStop(command)) stopAndDismissService(spokenConfirmation = true)
            }
        }

        job = scope.launch {
            val routeWithPoints = routeRepository.getRouteWithPointsByName(routeName)
            if (routeWithPoints == null || routeWithPoints.points.isEmpty()) {
                Log.w(TAG, "Route \"$routeName\" not found or has no points")
                val message = NavigationAnnouncements.routeNotFound(routeName, language)
                _phase.value = NavigationUiPhase.Failed(message)
                ttsManager.speak(message, flushQueue = true, utteranceId = "nav_route_not_found")
                stopVoiceListening()
                return@launch
            }

            Log.i(TAG, "Starting navigation: route=\"$routeName\" points=${routeWithPoints.points.size}")
            val navPoints = routeWithPoints.points.map { NavPoint(it.latitude, it.longitude) }
            val totalPoints = routeWithPoints.points.size
            ttsManager.speak(NavigationAnnouncements.routeStarted(routeName, language), flushQueue = true, utteranceId = "nav_start")

            // A plain local var isn't safe to share across the two
            // concurrently-launched coroutines below (they may run on
            // different Dispatchers.Default threads) -- an AtomicReference
            // gives correct cross-thread visibility without needing a full
            // StateFlow/combine() just for one mutable value.
            val latestHeading = AtomicReference<Double?>(null)
            launch {
                compassManager.headingUpdates().collect { reading ->
                    latestHeading.set(if (reading.isReliable) reading.azimuthDegrees else null)
                }
            }

            // The engine can't be built until the first real GPS fix comes
            // in -- its starting target is the recorded point NEAREST to
            // that fix (see NavigationEngine.nearestPointIndex), not always
            // navPoints[0], so navigation doesn't insist on walking back to
            // wherever recording happened to start if the user is actually
            // already further along the route.
            var engine: NavigationEngine? = null
            locationProvider.locationUpdates(intervalMs = NAV_LOCATION_INTERVAL_MS).collect { fix ->
                val heading = latestHeading.get()
                val currentEngine = engine ?: NavigationEngine(
                    navPoints,
                    startIndex = NavigationEngine.nearestPointIndex(navPoints, fix.latitude, fix.longitude)
                ).also { engine = it }
                val event = currentEngine.update(fix.latitude, fix.longitude, fix.accuracyMeters, heading)
                handleEvent(routeName, event, currentEngine, totalPoints, fix.accuracyMeters, heading)
            }
        }
    }

    private fun handleEvent(
        routeName: String,
        event: NavigationEvent,
        engine: NavigationEngine,
        totalPoints: Int,
        gpsAccuracyMeters: Float,
        headingDegrees: Double?
    ) {
        val now = System.currentTimeMillis()

        fun publish(instruction: String, distance: Double?) {
            _phase.value = NavigationUiPhase.Active(
                routeName = routeName,
                currentInstruction = instruction,
                distanceToNextPointMeters = distance,
                progressIndex = engine.currentTargetIndex,
                totalPoints = totalPoints,
                gpsAccuracyMeters = gpsAccuracyMeters,
                headingDegrees = headingDegrees,
                isDeviated = engine.isDeviated
            )
        }

        when (event) {
            is NavigationEvent.Guidance -> {
                val text = NavigationAnnouncements.forTurn(event.turn, language)
                val changed = text != lastTurnText
                if (changed || now - lastTurnSpokenAtMs >= TURN_REPEAT_COOLDOWN_MS) {
                    ttsManager.speak(text, flushQueue = false, utteranceId = "nav_turn")
                    lastTurnText = text
                    lastTurnSpokenAtMs = now
                }
                if (now - lastDistanceAnnouncedAtMs >= DISTANCE_ANNOUNCE_INTERVAL_MS) {
                    ttsManager.speak(
                        NavigationAnnouncements.distanceToNextPoint(event.distanceToNextPointMeters, language),
                        flushQueue = false,
                        utteranceId = "nav_distance"
                    )
                    lastDistanceAnnouncedAtMs = now
                }
                publish(text, event.distanceToNextPointMeters)
            }
            NavigationEvent.Deviated -> {
                if (lastDeviationSpoken != true) {
                    ttsManager.speak(NavigationAnnouncements.deviated(language), flushQueue = true, utteranceId = "nav_deviated")
                    lastDeviationSpoken = true
                }
                publish(NavigationAnnouncements.deviated(language), null)
            }
            NavigationEvent.BackOnRoute -> {
                if (lastDeviationSpoken != false) {
                    ttsManager.speak(NavigationAnnouncements.backOnRoute(language), flushQueue = false, utteranceId = "nav_back_on_route")
                    lastDeviationSpoken = false
                }
                publish(NavigationAnnouncements.backOnRoute(language), null)
            }
            NavigationEvent.ApproachingDestination -> {
                if (lastTurnText != APPROACHING_MARKER) {
                    ttsManager.speak(NavigationAnnouncements.approachingDestination(language), flushQueue = false, utteranceId = "nav_approaching")
                    lastTurnText = APPROACHING_MARKER
                    lastTurnSpokenAtMs = now
                }
                publish(NavigationAnnouncements.approachingDestination(language), null)
            }
            NavigationEvent.Arrived -> {
                ttsManager.speak(NavigationAnnouncements.arrived(language), flushQueue = true, utteranceId = "nav_arrived")
                _phase.value = NavigationUiPhase.Finished(routeName)
                stopAndDismissService(spokenConfirmation = false)
            }
            NavigationEvent.PoorGpsAccuracy -> {
                if (now - lastPoorGpsAnnouncedAtMs >= POOR_GPS_COOLDOWN_MS) {
                    ttsManager.speak(NavigationAnnouncements.poorGpsAccuracy(language), flushQueue = false, utteranceId = "nav_poor_gps")
                    lastPoorGpsAnnouncedAtMs = now
                }
                publish(NavigationAnnouncements.poorGpsAccuracy(language), null)
            }
        }
    }

    /** Stops navigation (arrival, voice "stop navigasi", or the UI's stop button); safe to call when not active. */
    fun stop(spokenConfirmation: Boolean) {
        Log.i(TAG, "Stopping navigation (spokenConfirmation=$spokenConfirmation)")
        job?.cancel()
        job = null
        stopVoiceListening()
        if (spokenConfirmation) {
            ttsManager.speak(NavigationAnnouncements.navigationStopped(language), flushQueue = true, utteranceId = "nav_stop")
        }
        // Reset from ANY non-Idle phase, not just Active: Arrived leaves
        // phase as Finished (not Active), and Failed is reachable too --
        // gating this on `is Active` left both of those stuck forever,
        // which in turn left NavigationScreen's "pop back once truly
        // stopped" LaunchedEffect(phase) waiting on an Idle transition that
        // would never come.
        if (_phase.value !is NavigationUiPhase.Idle) _phase.value = NavigationUiPhase.Idle
    }

    /**
     * Used by the two stop triggers this controller itself decides on
     * (voice "stop navigasi", and reaching the destination) -- unlike the
     * UI Stop button/notification action, which already goes through
     * [RouteNavigationService] and stops the service directly, those two
     * triggers previously called [stop] alone, which only tears down this
     * controller's own coroutines. Nothing ever told
     * [RouteNavigationService] to leave the foreground/dismiss its
     * notification, so the service (and its persistent "Navigating: X"
     * notification) kept running indefinitely after a voice stop or an
     * arrival -- this closes that gap by also asking the service to stop
     * itself. `spokenConfirmation = false` is passed to the service's
     * re-entrant [stop] call since the confirmation (or the "arrived"
     * announcement) was already spoken by the trigger that called this.
     */
    private fun stopAndDismissService(spokenConfirmation: Boolean) {
        stop(spokenConfirmation)
        RouteNavigationService.stop(context, spokenConfirmation = false)
    }

    /**
     * Stops only this controller's OWN "stop navigasi" collector -- does
     * NOT call [VoiceCommandManager.stopListening], which would globally
     * pause the shared mic for the whole app just because this one
     * feature's session ended (the same bug this had in
     * [com.apps.naviai.recording.RecordingViewModel] until it was fixed --
     * see that class's doc). Voice commands should keep working after
     * navigation stops, not go silent until something else happens to
     * resume them.
     */
    private fun stopVoiceListening() {
        voiceJob?.cancel()
        voiceJob = null
    }

    private fun resetAnnouncementState() {
        lastTurnText = null
        lastTurnSpokenAtMs = 0L
        lastDistanceAnnouncedAtMs = 0L
        lastPoorGpsAnnouncedAtMs = 0L
        lastDeviationSpoken = null
    }

    private companion object {
        const val TAG = "NavigationController"
        const val NAV_LOCATION_INTERVAL_MS = 1500L

        /** Re-speak the SAME turn instruction (nothing changed) at most this often -- avoids nagging with "walk straight" every second. */
        const val TURN_REPEAT_COOLDOWN_MS = 8000L
        const val DISTANCE_ANNOUNCE_INTERVAL_MS = 15000L
        const val POOR_GPS_COOLDOWN_MS = 20000L

        /** Sentinel stored in lastTurnText to dedupe the "approaching destination" announcement using the same change-detection path as turn text. */
        const val APPROACHING_MARKER = "\u0000approaching"
    }
}
