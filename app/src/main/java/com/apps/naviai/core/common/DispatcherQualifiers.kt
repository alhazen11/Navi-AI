package com.apps.naviai.core.common

import javax.inject.Qualifier

/**
 * Dedicated single-thread dispatcher for NCNN inference. Kept separate from
 * [kotlinx.coroutines.Dispatchers.Default] so a slow frame never competes
 * with (or is starved by) other CPU-bound work, and so at most one
 * inference call is ever in flight -- FrameAnalyzer relies on this to skip
 * frames instead of queuing them.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InferenceDispatcher

/** General-purpose IO dispatcher qualifier (asset copies, DataStore, TTS init). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Main-thread dispatcher, injected explicitly for testability. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
