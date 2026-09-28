package com.apps.naviai.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires when [GoHomeCommandMatcher] recognizes "NAVI, kembali"/"kembali ke home", from whichever
 * voice-command collector heard it. [com.apps.naviai.navigation.NAVINavHost] is this signal's only
 * collector: it clears the ENTIRE back stack and returns to
 * [com.apps.naviai.ui.screens.DetectionScreen], regardless of how deep the stack is or which screen
 * the command was heard on -- a plain `onBack()`/`popBackStack()` only undoes one level, which isn't
 * enough when, say, Navigation was reached via Detection -> Saved Routes -> Navigation.
 *
 * Deliberately carries no information about what to stop: each trigger (see [GoHomeCommandMatcher]'s
 * doc for the full list) stops whatever IT owns -- recording, navigation, the Pro Mode session --
 * before calling [trigger], since only that owner knows how to do so cleanly. This class is just the
 * "now go home" signal once that's done.
 */
@Singleton
class GoHomeSignal @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events

    fun trigger() {
        _events.tryEmit(Unit)
    }
}
