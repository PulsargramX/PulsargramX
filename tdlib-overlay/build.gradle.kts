import tgx.gradle.task.BuildTdlibOverlayTask
import tgx.gradle.task.GenerateTdlibOverlayTask
import java.util.Properties

plugins {
  id("java-toolchain-convention")
  id(libs.plugins.android.library.get().pluginId)
  id("tgx-module")
}

dependencies {
  implementation(libs.androidx.annotation)
}

val repository = project.isolated.rootProject.projectDirectory
val versions = Properties().apply {
  repository.file("version.properties").asFile.inputStream().use { load(it) }
}
val sourceInputs = files(
  fileTree(repository.dir("tdlib/source/td")) { exclude(".git", "build*/**", "example/android/build*/**") },
  fileTree(repository.dir("patches/tdlib")),
  repository.file("scripts/build-tdlib-overlay.py"),
  repository.file("tdlib/src/main/java/org/drinkless/tdlib/Client.java"),
  repository.file("version.properties")
)
val nativeJobs = providers.gradleProperty("tgx.nativeJobs").map { it.toInt() }.orElse(2)
val generateTdlib = tasks.register<GenerateTdlibOverlayTask>("generateTdlibOverlay") {
  group = "Setup"
  description = "Generates patched TDLib bindings without changing the pinned submodule"
  rootDir.set(repository)
  sdkDir.set(androidComponents.sdkComponents.sdkDirectory)
  sourceFiles.from(sourceInputs)
  jobs.set(nativeJobs)
  javaOutputDir.set(layout.buildDirectory.dir("generated/java"))
  revisionManifest.set(layout.buildDirectory.file("generated/revision.json"))
}

android {
  namespace = "org.drinkless.tdlib"
  defaultConfig {
    consumerProguardFiles(repository.file("tdlib/consumer-rules.pro").asFile)
  }
  lint { disable += "ScopedStorage" }
}
androidComponents.onVariants { variant ->
  variant.sources.java?.addGeneratedSourceDirectory(generateTdlib, GenerateTdlibOverlayTask::javaOutputDir)
}

mapOf("Primary" to versions.getProperty("version.ndk_primary"),
      "Legacy" to versions.getProperty("version.ndk_legacy")).forEach { (kind, ndk) ->
  mapOf("Arm64" to "arm64-v8a", "Arm32" to "armeabi-v7a", "X64" to "x86_64", "X86" to "x86")
    .filter { kind != "Legacy" || it.value in setOf("armeabi-v7a", "x86") }
    .forEach { (name, targetAbi) ->
      tasks.register<BuildTdlibOverlayTask>("buildTdlib$kind$name") {
        group = "Setup"
        description = "Builds patched TDLib JNI for $targetAbi with NDK $ndk"
        dependsOn(generateTdlib)
        rootDir.set(repository)
        sdkDir.set(androidComponents.sdkComponents.sdkDirectory)
        sourceFiles.from(sourceInputs)
        jobs.set(nativeJobs)
        ndkVersion.set(ndk)
        abi.set(targetAbi)
        opensslFiles.from(fileTree(repository.dir("tdlib/openssl/$ndk/$targetAbi")))
        nativeOutputDir.set(layout.buildDirectory.dir("native/$ndk/$targetAbi"))
      }
    }
}
