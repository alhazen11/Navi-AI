package com.apps.naviai.routenav

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin screen-facing wrapper around [NavigationController]/[RouteNavigationService]:
 * starting/stopping just tells the foreground service to do so (navigation
 * itself, and its state, live in the singleton controller and keep running
 * regardless of this ViewModel's own lifecycle) -- so this deliberately
 * does NOT stop navigation in onCleared(); only an explicit user action
 * (stop button, "stop navigasi") or arrival does that.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    navigationController: NavigationController
) : ViewModel() {

    val phase: StateFlow<NavigationUiPhase> = navigationController.phase

    fun startNavigation(routeName: String) {
        RouteNavigationService.start(context, routeName)
    }

    fun stopNavigation() {
        RouteNavigationService.stop(context)
    }
}
