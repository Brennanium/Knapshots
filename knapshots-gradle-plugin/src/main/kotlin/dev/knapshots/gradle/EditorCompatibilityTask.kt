package dev.knapshots.gradle

import dev.knapshots.testing.compatibility.AndroidStudioProvisioner
import dev.knapshots.testing.compatibility.AndroidStudioReleases
import dev.knapshots.testing.compatibility.IdePlatform
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Runs an external IDE regression suite")
public abstract class EditorCompatibilityTask : DefaultTask() {
  @get:Input public abstract val ideVersion: Property<String>
  @get:InputFile @get:Optional public abstract val driver: RegularFileProperty
  @get:InputDirectory @get:Optional public abstract val idePath: DirectoryProperty
  @get:InputFile @get:Optional public abstract val releaseFeed: RegularFileProperty
  @get:Internal public abstract val ideCache: DirectoryProperty

  @Option(option = "ide-version", description = "Android Studio version, build, or latest-stable")
  public fun ideVersion(value: String) { ideVersion.set(value) }

  @TaskAction public fun runCompatibility() {
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
    logger.lifecycle("Resolved Android Studio ${release.version} (build ${release.build})")
    fun run(ide: Path) {
      val info = listOf(ide.resolve("Contents/Resources/product-info.json"), ide.resolve("product-info.json"))
        .firstOrNull(Files::isRegularFile) ?: throw GradleException("No product-info.json in $ide")
      val json = JsonSlurper().parse(info.toFile()) as Map<*, *>
      val build = (json["buildNumber"] as? String)?.removePrefix("AI-")
      if (json["name"] != "Android Studio" || build != release.build) {
        throw GradleException("Installed IDE does not match Android Studio build ${release.build}: $ide")
      }
      val process = ProcessBuilder(executable.absolutePath, "--ide-path", ide.toAbsolutePath().toString())
        .directory(executable.parentFile).inheritIO().start()
      if (process.waitFor() != 0) throw GradleException("Editor compatibility driver failed for ${release.version}.")
    }
    val local = idePath.orNull?.asFile?.toPath()
    if (local != null) run(local) else AndroidStudioProvisioner(ideCache.get().asFile.toPath()).useIde(release, platform, ::run)
  }
}
