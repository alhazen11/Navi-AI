package com.apps.naviai.di

import com.apps.naviai.audio.Speaker
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.detection.detector.NcnnObjectDetector
import com.apps.naviai.detection.detector.ObjectDetector
import com.apps.naviai.detection.distance.DistanceEstimator
import com.apps.naviai.detection.distance.MonocularHeightDistanceEstimator
import com.apps.naviai.detection.risk.RiskAssessmentEngine
import com.apps.naviai.detection.tracking.ObjectTracker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionBindModule {
    @Binds
    @Singleton
    abstract fun bindObjectDetector(impl: NcnnObjectDetector): ObjectDetector

    @Binds
    @Singleton
    abstract fun bindDistanceEstimator(impl: MonocularHeightDistanceEstimator): DistanceEstimator

    @Binds
    @Singleton
    abstract fun bindSpeaker(impl: TextToSpeechManager): Speaker
}

@Module
@InstallIn(SingletonComponent::class)
object DetectionProvidesModule {
    // Stateful (per-track history) but held for the app's lifetime and
    // reset explicitly by DetectionViewModel when a session starts/stops,
    // rather than recreated -- recreating would need to be threaded through
    // every injection site instead of a single reset() call.
    @Provides
    @Singleton
    fun provideObjectTracker(): ObjectTracker = ObjectTracker()

    @Provides
    @Singleton
    fun provideRiskAssessmentEngine(): RiskAssessmentEngine = RiskAssessmentEngine()
}
