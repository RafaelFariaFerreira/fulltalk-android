// Runs the real speech model over long recordings, on the desktop. Not part of `./gradlew test`:
// it needs about 1 GB of downloads (the model, sherpa-onnx for the JVM, a slice of FLEURS) and a
// few minutes of CPU. Run it with
//
//     ./gradlew :eval:test
//
// Downloads are pinned by SHA-256 and kept in ~/.cache/fulltalk-eval (or -Pfulltalk.evalData=DIR).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

// Only on request: `./gradlew test` or `build` at the root must not start a 1 GB download.
val requested = gradle.startParameter.taskNames.any { it.startsWith(":eval:") || it.startsWith("eval:") }

val evalData: File = providers.gradleProperty("fulltalk.evalData").orNull?.let(::File)
    ?: File(System.getProperty("user.home"), ".cache/fulltalk-eval")

/** Downloads [url] to [target] once, refusing anything whose SHA-256 is not [sha256]. */
fun download(url: String, target: File, sha256: String) {
    if (target.isFile) return
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
    if (actual != sha256) {
        part.delete()
        throw GradleException("$url: expected SHA-256 $sha256, got $actual")
    }
    part.renameTo(target)
}

// The same sherpa-onnx release the app ships (see app/build.gradle.kts), built for the JVM.
val sherpaVersion = "2.0.1"
val sherpaJars = mapOf(
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "8320e2457b6e38dd624fb619d3552e11ef78ffeb394622056126b16f2fd99d12",
    "sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar" to "744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f",
)
val sherpaDir = File(evalData, "sherpa-onnx-$sherpaVersion")

val fetchSherpaOnnxJvm = tasks.register("fetchSherpaOnnxJvm") {
    description = "Downloads sherpa-onnx for the JVM (Linux x86_64) and verifies its checksums."
    onlyIf("run with :eval:test") { requested }
    outputs.dir(sherpaDir)
    doLast {
        for ((name, sha256) in sherpaJars) {
            download("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$name", File(sherpaDir, name), sha256)
        }
    }
}

// FLEURS (Google, CC-BY-4.0): read sentences with their transcription. The Brazilian Portuguese dev split.
val fleursRevision = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
val fleursDir = File(evalData, "fleurs-pt_br-dev")

val fetchFleurs = tasks.register("fetchFleurs") {
    description = "Downloads the pt_br dev split of FLEURS and verifies its checksums."
    onlyIf("run with :eval:test") { requested }
    outputs.dir(fleursDir)
    doLast {
        val base = "https://huggingface.co/datasets/google/fleurs/resolve/$fleursRevision/data/pt_br"
        download("$base/dev.tsv", File(fleursDir, "dev.tsv"), "69a60f2ef2964da22ac38c3fe223a5ff5d59b9d7fb8ccfcc7003737fc7a1f69c")
        val archive = File(fleursDir, "dev.tar.gz")
        download("$base/audio/dev.tar.gz", archive, "15f2a075859c3b6448e81c867f9c2a77694b3deab6b24ebc632de09bbf401838")
        if (!File(fleursDir, "dev").isDirectory) {
            copy {
                from(tarTree(resources.gzip(archive)))
                into(fleursDir)
            }
        }
    }
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(files(sherpaJars.keys.map { File(sherpaDir, it) }))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fetchSherpaOnnxJvm, fetchFleurs)
    onlyIf("run with :eval:test") { requested }
    systemProperty("fulltalk.evalData", evalData.path)
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
}

tasks.named("compileTestKotlin") {
    dependsOn(fetchSherpaOnnxJvm)
    onlyIf("run with :eval:test") { requested }
}
