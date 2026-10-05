package tgx.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Shared driver for generated bindings and ABI-specific JNI outputs. */
abstract class TdlibOverlayTask : DefaultTask() {
  @get:Internal abstract val rootDir: DirectoryProperty
  @get:Internal abstract val sdkDir: DirectoryProperty
  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection
  @get:Input abstract val jobs: Property<Int>
  @get:Inject abstract val exec: ExecOperations

  protected fun run(vararg args: String) {
    exec.exec {
      workingDir(rootDir.get().asFile)
      commandLine(listOf("python3", "scripts/build-tdlib-overlay.py") + args + listOf(
        "--root", rootDir.get().asFile.absolutePath,
        "--sdk", sdkDir.get().asFile.absolutePath, "--jobs", jobs.get().toString()
      ))
    }
  }
}

@CacheableTask
abstract class GenerateTdlibOverlayTask : TdlibOverlayTask() {
  @get:OutputDirectory abstract val javaOutputDir: DirectoryProperty
  @get:OutputFile abstract val revisionManifest: org.gradle.api.file.RegularFileProperty
  @TaskAction fun generate() = run("generate")
}

// Native outputs embed absolute debug paths. Keep local incremental state, but do not
// upload it to the shared Gradle build cache.
@DisableCachingByDefault(because = "JNI compilation uses persistent local CMake state")
abstract class BuildTdlibOverlayTask : TdlibOverlayTask() {
  @get:Input abstract val ndkVersion: Property<String>
  @get:Input abstract val abi: Property<String>
  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val opensslFiles: ConfigurableFileCollection
  @get:OutputDirectory abstract val nativeOutputDir: DirectoryProperty
  @TaskAction fun build() = run("native", "--ndk", ndkVersion.get(), "--abi", abi.get())
}
