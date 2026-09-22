package com.apps.naviai.data.model

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Copies the bundled NCNN model out of assets/ into app-private storage.
 * NCNN's `Net::load_param`/`load_model` take a filesystem path, they cannot
 * read directly out of the APK's compressed assets, so this extraction step
 * is mandatory on every cold start (skipped if the files are already
 * present and match in size).
 */
class ModelAssetManager(private val context: Context) {

    data class ModelFiles(
        val paramPath: String,
        val binPath: String,
        val labels: Array<String>
    )

    fun ensureModelExtracted(): ModelFiles {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val paramFile = copyAssetIfNeeded("models/$PARAM_ASSET", File(modelDir, PARAM_ASSET))
        val binFile = copyAssetIfNeeded("models/$BIN_ASSET", File(modelDir, BIN_ASSET))
        val labels = loadLabels()

        check(labels.size == EXPECTED_CLASS_COUNT) {
            "coco.names must contain exactly $EXPECTED_CLASS_COUNT labels, found ${labels.size}"
        }

        return ModelFiles(paramFile.absolutePath, binFile.absolutePath, labels)
    }

    private fun loadLabels(): Array<String> =
        context.assets.open("models/$LABELS_ASSET").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.toTypedArray()

    private fun copyAssetIfNeeded(assetPath: String, target: File): File {
        // openFd() requires the asset be stored uncompressed (see the
        // noCompress config in app/build.gradle.kts); if that's ever not
        // the case -- e.g. a stale/misconfigured APK -- fall back to always
        // copying rather than crashing on the size check.
        val assetSize = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrNull()
        if (assetSize != null && target.exists() && target.length() == assetSize) {
            return target
        }
        try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return target
    }

    companion object {
        private const val PARAM_ASSET = "yolo_model.ncnn.param"
        private const val BIN_ASSET = "yolo_model.ncnn.bin"
        private const val LABELS_ASSET = "coco.names"
        const val EXPECTED_CLASS_COUNT = 80
    }
}
