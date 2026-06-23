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
            // The Parakeet model assets are downloaded into
            // build/generated/assets/parakeet by the `downloadParakeetAssets`
            // task and wired as a runtime assets source root.
            assets.srcDirs("src/main/assets", layout.buildDirectory.dir("generated/assets/parakeet"))
            jniLibs.srcDirs("src/main/jniLibs", layout.buildDirectory.dir("generated/jniLibs"))
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
}

// ---------------------------------------------------------------------------
// Parakeet native library (Rust via cargo-ndk)
// ---------------------------------------------------------------------------

val parakeetTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
)

val parakeetBuildDir = layout.buildDirectory.dir("generated/parakeet")
val parakeetJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

val buildParakeetNative by tasks.registering {
    group = "parakeet"
    description = "Cross-compiles the Subspace Parakeet Rust crate for Android ABIs using cargo-ndk."
    val outputsDir = parakeetJniLibsDir
    outputs.dir(outputsDir)
    val rootDir = rootProject.projectDir
    inputs.dir(file("$rootDir/rust/subspace-parakeet/src"))
    inputs.file(file("$rootDir/rust/Cargo.toml"))
    inputs.file(file("$rootDir/rust/subspace-parakeet/Cargo.toml"))
    doLast {
        val baseDir = file("$rootDir/rust")
        val cargo = resolveOnPath("cargo") ?: "cargo"
        val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("NDK_DIR")
        require(!ndk.isNullOrBlank()) {
            "ANDROID_NDK_HOME/NDK_DIR must be set (use `nix develop`)."
        }
        parakeetTargets.forEach { (abi, target) ->
            val outDir = outputsDir.get().dir(abi)
            outDir.asFile.mkdirs()
            val process = ProcessBuilder(
                cargo, "ndk",
                "-t", target,
                "--manifest-path", "subspace-parakeet/Cargo.toml",
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
                throw GradleException("cargo ndk build failed for $abi:\n$output")
            }
            val so = file("$baseDir/target/$target/release/libsubspace_parakeet.so")
            if (!so.exists()) {
                throw GradleException("missing $so after cargo ndk build")
            }
            so.copyTo(outDir.file("libsubspace_parakeet.so").asFile, overwrite = true)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildParakeetNative)
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
