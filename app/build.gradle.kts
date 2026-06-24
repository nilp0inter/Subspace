import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.nilp0inter.subspace"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.nilp0inter.subspace"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            // Rust native build is invoked by the preBuild task. No CMake/ndk-build
            // project is declared because cargo-ndk manages the toolchain.
            ndkBuild {
                // no-op; placeholder for Gradle's externalNativeBuild DSL.
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            // The Parakeet and Supertonic model assets are downloaded into
            // build/generated/assets/{parakeet,supertonic} by their respective
            // download tasks and wired as runtime assets source roots.
            assets.srcDirs(
                "src/main/assets",
                layout.buildDirectory.dir("generated/assets/parakeet"),
                layout.buildDirectory.dir("generated/assets/supertonic"),
            )
            jniLibs.srcDirs("src/main/jniLibs", layout.buildDirectory.dir("generated/jniLibs"))
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Both `subspace-parakeet` and `subspace-supertonic` dynamically
            // load the same `libonnxruntime.so` (provided by the ONNX Runtime
            // Android AAR). The AAR may also package its own copy; pick the
            // first one and deduplicate the rest so the APK ships exactly one
            // ONNX Runtime native library shared by both native crates.
            pickFirsts += setOf("**/libonnxruntime.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20231013")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// ---------------------------------------------------------------------------
// Native libraries (Rust via cargo-ndk)
// ---------------------------------------------------------------------------

val nativeTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
)

val jniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

/**
 * Cross-compile a Rust cdylib crate for the active Android ABIs using
 * cargo-ndk, then copy the resulting `.so` into the generated jniLibs dir.
 *
 * Both `subspace-parakeet` and `subspace-supertonic` are built this way and
 * share the same ONNX Runtime native dependency (`libonnxruntime.so`) provided
 * by the ONNX Runtime Android AAR. The app therefore ships exactly one ONNX
 * Runtime native library (see `packaging.jniLibs.pickFirsts`).
 */
fun registerNativeBuildTask(
    taskName: String,
    crateDir: String,
    libName: String,
): TaskProvider<org.gradle.api.DefaultTask> =
    tasks.register<org.gradle.api.DefaultTask>(taskName) {
        group = "native"
        description = "Cross-compiles the $crateDir Rust crate for Android ABIs using cargo-ndk."
        val outputsDir = jniLibsDir
        outputs.dir(outputsDir)
        val rootDir = rootProject.projectDir
        inputs.dir(file("$rootDir/rust/$crateDir/src"))
        inputs.file(file("$rootDir/rust/Cargo.toml"))
        inputs.file(file("$rootDir/rust/$crateDir/Cargo.toml"))
        doLast {
            val baseDir = file("$rootDir/rust")
            val cargo = resolveOnPath("cargo") ?: "cargo"
            val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("NDK_DIR")
            require(!ndk.isNullOrBlank()) {
                "ANDROID_NDK_HOME/NDK_DIR must be set (use `nix develop`)."
            }
            nativeTargets.forEach { (abi, target) ->
                val outDir = outputsDir.get().dir(abi)
                outDir.asFile.mkdirs()
                val process = ProcessBuilder(
                    cargo, "ndk",
                    "-t", target,
                    "--manifest-path", "$crateDir/Cargo.toml",
                    "build", "--release",
                ).apply {
                    directory(baseDir)
                    redirectErrorStream(true)
                    val env = environment()
                    env["ANDROID_NDK_HOME"] = ndk
                    env["CARGO_NDK_TARGET_API"] = "31"
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()
                if (exit != 0) {
                    throw GradleException("cargo ndk build failed for $abi ($crateDir):\n$output")
                }
                val so = file("$baseDir/target/$target/release/$libName")
                if (!so.exists()) {
                    throw GradleException("missing $so after cargo ndk build")
                }
                so.copyTo(outDir.file(libName).asFile, overwrite = true)
            }
        }
    }

val buildParakeetNative = registerNativeBuildTask(
    "buildParakeetNative",
    "subspace-parakeet",
    "libsubspace_parakeet.so",
)

val buildOggNative = registerNativeBuildTask(
    "buildOggNative",
    "subspace-ogg",
    "libsubspace_ogg.so",
)

val buildSupertonicNative = registerNativeBuildTask(
    "buildSupertonicNative",
    "subspace-supertonic",
    "libsubspace_supertonic.so",
)

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildOggNative)
    dependsOn(buildParakeetNative)
    dependsOn(buildSupertonicNative)
}

// ---------------------------------------------------------------------------
// Parakeet model asset download (int8 ONNX, vocab, config)
// ---------------------------------------------------------------------------

val parakeetAssetDir = layout.buildDirectory.dir("generated/assets/parakeet")

val parakeetAssetSpecs = listOf(
    ParakeetAsset(
        name = "encoder-model.int8.onnx",
        sha256 = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
    ),
    ParakeetAsset(
        name = "decoder_joint-model.int8.onnx",
        sha256 = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
    ),
    ParakeetAsset(
        name = "nemo128.onnx",
        sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
    ),
    ParakeetAsset(
        name = "vocab.txt",
        sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
    ),
    ParakeetAsset(
        name = "config.json",
        sha256 = "666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466",
    ),
)

val downloadParakeetAssets by tasks.registering {
    group = "parakeet"
    description = "Downloads Parakeet v3 int8 ONNX model, vocab, and config with SHA-256 verification via the HuggingFace Hub CLI."
    val outDir = parakeetAssetDir
    outputs.dir(outDir)
    parakeetAssetSpecs.forEach { spec ->
        outputs.file(outDir.map { it.file(spec.name) })
    }
    doLast {
        outDir.get().asFile.mkdirs()
        val repo = "smcleod/parakeet-tdt-0.6b-v3-int8"
        val hfBin = System.getenv("HF_BIN") ?: resolveOnPath("hf") ?: "hf"
        logger.lifecycle("Downloading Parakeet assets from HuggingFace repo {} via {}", repo, hfBin)
        val download = ProcessBuilder(
            hfBin, "download", repo,
            "--local-dir", outDir.get().asFile.absolutePath,
            *parakeetAssetSpecs.map { it.name }.toTypedArray(),
        ).apply {
            redirectErrorStream(true)
            val env = environment()
            env["HF_HUB_ENABLE_HF_TRANSFER"] = env.getOrDefault("HF_HUB_ENABLE_HF_TRANSFER", "1")
        }.start()
        val downloadOutput = download.inputStream.bufferedReader().readText()
        val downloadExit = download.waitFor()
        if (downloadExit != 0) {
            throw GradleException("hf download failed:\n$downloadOutput")
        }
        parakeetAssetSpecs.forEach { spec ->
            val target = outDir.get().file(spec.name).asFile
            check(target.exists()) {
                "hf download did not produce expected file ${spec.name} at ${target.absolutePath}"
            }
            val actual = sha256OfFile(target)
            check(actual == spec.sha256) {
                "SHA-256 mismatch for ${spec.name}: expected ${spec.sha256}, got $actual"
            }
            logger.lifecycle("Parakeet asset {} verified", spec.name)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadParakeetAssets)
}

// ---------------------------------------------------------------------------
// Supertonic model asset download (ONNX, configs, voice styles)
// ---------------------------------------------------------------------------

val supertonicAssetDir = layout.buildDirectory.dir("generated/assets/supertonic")

// Supertonic 3 assets live under `onnx/` and `voice_styles/` in the
// `Supertone/supertonic-3` HuggingFace repo. They are downloaded into the flat
// generated assets dir (flattening the HF subdir layout) so the Android asset
// source set and `SupertonicAssetExtractor` see them at the top level.
val supertonicAssetSpecs = listOf(
    SupertonicAsset(
        hfPath = "onnx/duration_predictor.onnx",
        localName = "duration_predictor.onnx",
        sha256 = "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
    ),
    SupertonicAsset(
        hfPath = "onnx/text_encoder.onnx",
        localName = "text_encoder.onnx",
        sha256 = "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
    ),
    SupertonicAsset(
        hfPath = "onnx/vector_estimator.onnx",
        localName = "vector_estimator.onnx",
        sha256 = "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
    ),
    SupertonicAsset(
        hfPath = "onnx/vocoder.onnx",
        localName = "vocoder.onnx",
        sha256 = "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
    ),
    SupertonicAsset(
        hfPath = "onnx/tts.json",
        localName = "tts.json",
        sha256 = "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
    ),
    SupertonicAsset(
        hfPath = "onnx/unicode_indexer.json",
        localName = "unicode_indexer.json",
        sha256 = "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F1.json",
        localName = "F1.json",
        sha256 = "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F2.json",
        localName = "F2.json",
        sha256 = "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F3.json",
        localName = "F3.json",
        sha256 = "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F4.json",
        localName = "F4.json",
        sha256 = "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F5.json",
        localName = "F5.json",
        sha256 = "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M1.json",
        localName = "M1.json",
        sha256 = "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M2.json",
        localName = "M2.json",
        sha256 = "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M3.json",
        localName = "M3.json",
        sha256 = "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M4.json",
        localName = "M4.json",
        sha256 = "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M5.json",
        localName = "M5.json",
        sha256 = "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2",
    ),
)

val downloadSupertonicAssets by tasks.registering {
    group = "supertonic"
    description = "Downloads Supertonic 3 ONNX models, configs, and voice styles with SHA-256 verification via the HuggingFace Hub CLI."
    val outDir = supertonicAssetDir
    outputs.dir(outDir)
    supertonicAssetSpecs.forEach { spec ->
        outputs.file(outDir.map { it.file(spec.localName) })
    }
    doLast {
        outDir.get().asFile.mkdirs()
        val repo = "Supertone/supertonic-3"
        val hfBin = System.getenv("HF_BIN") ?: resolveOnPath("hf") ?: "hf"
        logger.lifecycle("Downloading Supertonic assets from HuggingFace repo {} via {}", repo, hfBin)
        // Download each asset into the flat output dir, flattening the HF
        // `onnx/` and `voice_styles/` subdirs. hf downloads to the subdir path
        // under local-dir, so we download into a staging dir and move files.
        val staging = outDir.get().asFile.resolve(".staging").apply { mkdirs() }
        try {
            val download = ProcessBuilder(
                hfBin, "download", repo,
                "--local-dir", staging.absolutePath,
                *supertonicAssetSpecs.map { it.hfPath }.toTypedArray(),
            ).apply {
                redirectErrorStream(true)
                val env = environment()
                env["HF_HUB_ENABLE_HF_TRANSFER"] = env.getOrDefault("HF_HUB_ENABLE_HF_TRANSFER", "1")
            }.start()
            val downloadOutput = download.inputStream.bufferedReader().readText()
            val downloadExit = download.waitFor()
            if (downloadExit != 0) {
                throw GradleException("hf download failed:\n$downloadOutput")
            }
            supertonicAssetSpecs.forEach { spec ->
                val staged = staging.resolve(spec.hfPath)
                check(staged.exists()) {
                    "hf download did not produce expected file ${spec.hfPath} at ${staged.absolutePath}"
                }
                val target = outDir.get().file(spec.localName).asFile
                staged.copyTo(target, overwrite = true)
                val actual = sha256OfFile(target)
                check(actual == spec.sha256) {
                    "SHA-256 mismatch for ${spec.localName}: expected ${spec.sha256}, got $actual"
                }
                logger.lifecycle("Supertonic asset {} verified", spec.localName)
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadSupertonicAssets)
}

fun sha256OfFile(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun resolveOnPath(executable: String): String? {
    val path = System.getenv("PATH") ?: return null
    for (dir in path.split(File.pathSeparatorChar)) {
        if (dir.isBlank()) continue
        val candidate = File(dir, executable)
        if (candidate.isFile && candidate.canExecute()) {
            return candidate.absolutePath
        }
    }
    return null
}

data class ParakeetAsset(
    val name: String,
    val sha256: String,
)

data class SupertonicAsset(
    val hfPath: String,
    val localName: String,
    val sha256: String,
)
