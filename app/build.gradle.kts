import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.fulltalk.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.fulltalk.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Phones are arm64; x86_64 is there for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        // targetSdk stays one release behind compileSdk until the app has been
        // exercised on an Android 17 image; the libraries only need it to compile.
        disable += "OldTargetApi"
        // Dependency versions are bumped deliberately, not on every lint run.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    // The speech runtime ships its own native libraries; keep them uncompressed so they load fast.
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

// sherpa-onnx (ONNX Runtime plus the Parakeet decoder) is only published as a
// release asset, so it is fetched here and pinned by checksum instead of
// committing a 49 MB binary.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar")

val fetchSherpaOnnx = tasks.register("fetchSherpaOnnx") {
    description = "Downloads the sherpa-onnx Android library and verifies its checksum."
    val target = sherpaAar.asFile
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    val expected = sherpaSha256
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            part.delete()
            throw GradleException("sherpa-onnx checksum mismatch: expected $expected, got $actual")
        }
        part.renameTo(target)
    }
}

tasks.named("preBuild") { dependsOn(fetchSherpaOnnx) }

dependencies {
    implementation(project(":core"))
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
