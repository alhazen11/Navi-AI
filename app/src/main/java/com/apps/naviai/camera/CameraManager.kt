package com.apps.naviai.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin, testable wrapper around CameraX provider/use-case setup and lifecycle binding. */
@Singleton
class CameraManager @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun buildPreviewUseCase(): Preview = Preview.Builder().build()

    fun buildAnalysisUseCase(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    fun hasLensFacing(provider: ProcessCameraProvider, @CameraSelector.LensFacing lensFacing: Int): Boolean =
        try {
            provider.hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
        } catch (t: Exception) {
            false
        }

    /** Unbinds any previous use cases and binds fresh ones for [lensFacing]. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        provider: ProcessCameraProvider,
        preview: Preview,
        analysis: ImageAnalysis,
        @CameraSelector.LensFacing lensFacing: Int
    ): Camera {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        return provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
    }

    fun unbindAll(provider: ProcessCameraProvider) {
        provider.unbindAll()
    }
}
