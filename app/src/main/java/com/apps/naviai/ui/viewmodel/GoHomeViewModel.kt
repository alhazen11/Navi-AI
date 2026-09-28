package com.apps.naviai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.apps.naviai.navigation.GoHomeSignal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Trivial Hilt bridge so [com.apps.naviai.navigation.NAVINavHost] -- composed directly under
 * [com.apps.naviai.MainActivity], not inside a `composable { }` route -- can collect
 * [GoHomeSignal] via the usual `hiltViewModel()` call. [GoHomeSignal] itself is a plain
 * `@Singleton`, not a ViewModel, since every voice-command collector that triggers it
 * ([com.apps.naviai.ui.viewmodel.DetectionViewModel], [com.apps.naviai.recording.RecordingViewModel],
 * [com.apps.naviai.routenav.NavigationController]) needs to inject it too, and only one of
 * those is itself a ViewModel.
 */
@HiltViewModel
class GoHomeViewModel @Inject constructor(goHomeSignal: GoHomeSignal) : ViewModel() {
    val events: SharedFlow<Unit> = goHomeSignal.events
}
