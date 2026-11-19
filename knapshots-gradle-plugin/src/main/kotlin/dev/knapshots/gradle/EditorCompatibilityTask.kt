package dev.knapshots.gradle

import dev.knapshots.testing.compatibility.AndroidStudioProvisioner
import dev.knapshots.testing.compatibility.AndroidStudioReleases
import dev.knapshots.testing.compatibility.IdePlatform
import dev.knapshots.testing.compatibility.IdeKotlinVersion
import dev.knapshots.testing.compatibility.KefsRelease
import dev.knapshots.testing.compatibility.KefsReleases
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Runs an external IDE regression suite")
public abstract class EditorCompatibilityTask : DefaultTask() {
  init { outputs.upToDateWhen { false } }

  @get:Input public abstract val ideVersion: Property<String>
  @get:InputFile @get:Optional public abstract val driver: RegularFileProperty
  @get:InputDirectory @get:Optional public abstract val idePath: DirectoryProperty
  @get:InputFile @get:Optional public abstract val releaseFeed: RegularFileProperty
  @get:Internal public abstract val ideCache: DirectoryProperty
  @get:Input public abstract val useKefs: Property<Boolean>
  @get:Input public abstract val driverEnvironment: MapProperty<String, String>
  @get:Input @get:Optional public abstract val kefsVersion: Property<String>
  @get:Input public abstract val useXvfb: Property<Boolean>
  @get:OutputFile public abstract val reportFile: RegularFileProperty

  @Option(option = "ide-version", description = "Android Studio version, build, or latest-stable")
  public fun ideVersion(value: String) { ideVersion.set(value) }

  @TaskAction public fun runCompatibility() {
    val report = linkedMapOf<String, Any?>("selector" to ideVersion.orNull, "result" to "failed")
    try {
      runCompatibility(report)
      report["result"] = "passed"
    } catch (error: Exception) {
      report["error"] = error.message ?: error.javaClass.name
      throw error
    } finally {
      val file = reportFile.get().asFile.toPath()
      Files.createDirectories(file.parent)
      Files.writeString(file, JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n")
    }
  }

  private fun runCompatibility(report: MutableMap<String, Any?>) {
    val selector = ideVersion.orNull ?: throw GradleException("Specify --ide-version.")
    val executable = driver.orNull?.asFile ?: throw GradleException("Configure knapshotsCompatibility.editorDriver.")
    if (!Files.isExecutable(executable.toPath())) throw GradleException("Editor compatibility driver is not executable: $executable")
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val platform = when {
      os.contains("mac") && arch in setOf("aarch64", "arm64") -> IdePlatform.MAC_ARM64
      os.contains("linux") && arch in setOf("amd64", "x86_64") -> IdePlatform.LINUX_X64
      else -> throw GradleException("Automatic IDE provisioning is unsupported on $os/$arch.")
    }
    val feed = releaseFeed.orNull?.asFile?.inputStream() ?: URI("https://jb.gg/android-studio-releases-list.xml").toURL().openConnection().apply {
      connectTimeout = 60_000; readTimeout = 60_000
    }.getInputStream()
    val release = feed.use { AndroidStudioReleases.select(selector, it, platform) }
    report["androidStudio"] = mapOf("name" to release.name, "version" to release.version, "build" to release.build, "url" to release.url, "sha256" to release.sha256)
    logger.lifecycle("Resolved Android Studio ${release.version} (build ${release.build})")
    fun run(ide: Path) {
      val info = listOf(ide.resolve("Contents/Resources/product-info.json"), ide.resolve("product-info.json"))
        .firstOrNull(Files::isRegularFile) ?: throw GradleException("No product-info.json in $ide")
      val json = JsonSlurper().parse(info.toFile()) as Map<*, *>
      val build = (json["buildNumber"] as? String)?.removePrefix("AI-")
      if (json["name"] != "Android Studio" || build != release.build) {
        throw GradleException("Installed IDE does not match Android Studio build ${release.build}: $ide")
      }
      var archive: Path? = null
      try {
        val args = mutableListOf(executable.absolutePath, "--ide-path", ide.toAbsolutePath().toString())
        if (useKefs.get()) {
          val url = URI("https://plugins.jetbrains.com/api/plugins/26480/updates?size=100").toURL()
          val updates = url.openConnection().apply { connectTimeout = 60_000; readTimeout = 60_000 }.getInputStream().bufferedReader().use { JsonSlurper().parse(it) as List<*> }
          val choices = updates.map { it as Map<*, *> }.map { item ->
            KefsRelease((item["id"] as Number).toLong(), item["version"] as String, item["file"] as String,
              item["since"] as? String, item["until"] as? String, (item["cdate"] as? Number)?.toLong() ?: 0,
              item["approve"] == true, item["listed"] == true, item["hidden"] == true, item["channel"] as? String)
          }
          val selected = KefsReleases.select(release.build, choices, kefsVersion.orNull)
          val artifactPath = selected.file.trimStart('/')
          require(artifactPath.isNotBlank() && !artifactPath.split('/').contains("..") && !artifactPath.contains('\\')) { "Invalid KEFS Marketplace artifact path." }
          archive = Files.createTempFile("knapshots-kefs-", ".zip")
          URI("https://plugins.jetbrains.com/files/$artifactPath").toURL().openStream().use { Files.copy(it, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
          val compiler = IdeKotlinVersion.discover(ide, release.build, archive)
          report["kefsVersion"] = selected.version
          report["ideKotlinVersion"] = compiler
          logger.lifecycle("Resolved KEFS ${selected.version}; IDE Kotlin compiler $compiler")
          args.addAll(listOf("--ide-kotlin-version", compiler, "--kefs-version", selected.version))
        }
        if (useXvfb.get()) {
          require(platform == IdePlatform.LINUX_X64) { "Xvfb is only supported on Linux." }
          args.addAll(0, listOf("xvfb-run", "-a"))
        }
        val processBuilder = ProcessBuilder(args).directory(executable.parentFile).redirectErrorStream(true)
        processBuilder.environment().putAll(driverEnvironment.get())
        processBuilder.environment()["KNAPSHOTS_IDE_PATH"] = ide.toAbsolutePath().toString()
        archive?.let { processBuilder.environment()["KNAPSHOTS_KEFS_ARCHIVE"] = it.toAbsolutePath().toString() }
        val process = processBuilder.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle(it) } }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("Editor compatibility driver failed for ${release.version} (exit $exitCode).")
      } finally { archive?.let(Files::deleteIfExists) }
    }
    val local = idePath.orNull?.asFile?.toPath()
    if (local != null) run(local) else AndroidStudioProvisioner(ideCache.get().asFile.toPath()).useIde(release, platform, ::run)
  }
}
