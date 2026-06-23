package dev.nilp0inter.subspace.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts Supertonic model assets from the APK `assets/` directory to the app
 * files storage so that the native `subspace_supertonic` library can load them
 * by filesystem path.
 *
 * Extraction is idempotent: a `.subspace_assets_version` marker records the
 * extracted asset version, and matching the marker skips re-extraction.
 *
 * The native Rust crate (`subspace_supertonic::asset`) owns the marker logic for
 * host-side unit tests; this Kotlin helper mirrors the same convention so the
 * marker written by either side is recognized by both.
 *
 * Voice style JSONs (F1-F5, M1-M5) are extracted alongside the model assets
 * into the same directory so the native bridge can load them by path.
 */
object SupertonicAssetExtractor {
    const val MARKER_NAME = ".subspace_assets_version"
    const val MODEL_DIR_NAME = "supertonic-3"
    val MODEL_ASSET_NAMES = listOf(
        "duration_predictor.onnx",
        "text_encoder.onnx",
        "vector_estimator.onnx",
        "vocoder.onnx",
        "tts.json",
        "unicode_indexer.json",
    )
    val VOICE_STYLE_NAMES = listOf(
        "F1.json", "F2.json", "F3.json", "F4.json", "F5.json",
        "M1.json", "M2.json", "M3.json", "M4.json", "M5.json",
    )
    val ASSET_NAMES: List<String> = MODEL_ASSET_NAMES + VOICE_STYLE_NAMES

    /**
     * Extract assets into [context].filesDir/`supertonic-3` and return that
     * directory. If the marker already matches [version], the existing
     * directory is returned without re-copying.
     */
    fun extract(context: Context, version: String): File {
        val destDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!destDir.exists()) destDir.mkdirs()
        val marker = File(destDir, MARKER_NAME)
        if (marker.exists() && marker.readText().trim() == version) {
            return destDir
        }

        val assetManager = context.assets
        for (name in ASSET_NAMES) {
            val target = File(destDir, name)
            assetManager.open(name).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }
        marker.writeText(version)
        return destDir
    }
}