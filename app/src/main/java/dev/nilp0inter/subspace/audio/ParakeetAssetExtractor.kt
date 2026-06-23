package dev.nilp0inter.subspace.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts Parakeet model assets from the APK `assets/` directory to the app
 * files storage so that the native `transcribe-rs` library can load them by
 * filesystem path.
 *
 * Extraction is idempotent: a `.subspace_assets_version` marker records the
 * extracted asset version, and matching the marker skips re-extraction.
 *
 * The native Rust crate (`subspace_parakeet::asset`) owns the marker logic for
 * host-side unit tests; this Kotlin helper mirrors the same convention so the
 * marker written by either side is recognized by both.
 */
object ParakeetAssetExtractor {
    const val MARKER_NAME = ".subspace_assets_version"
    const val MODEL_DIR_NAME = "parakeet-tdt-0.6b-v3-int8"
    val ASSET_NAMES = listOf(
        "encoder-model.int8.onnx",
        "decoder_joint-model.int8.onnx",
        "nemo128.onnx",
        "vocab.txt",
        "config.json",
    )

    /**
     * Extract assets into [context].filesDir/`parakeet-tdt-0.6b-v3-int8` and
     * return that directory. If the marker already matches [version], the
     * existing directory is returned without re-copying.
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