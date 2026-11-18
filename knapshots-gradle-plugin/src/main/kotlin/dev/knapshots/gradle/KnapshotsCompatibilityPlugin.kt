package dev.knapshots.gradle

import dev.knapshots.testing.compatibility.CompilerCompatibility
import dev.knapshots.testing.compatibility.CompilerCompatibilityDriver
import dev.knapshots.testing.compatibility.CompatibilityResult
import dev.knapshots.testing.compatibility.CompatibilityStatus
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files

public abstract class CompatibilityExtension {
  /** Executable accepting `--kotlin-version <version>` and returning a process exit code. */
  public abstract val compilerDriver: RegularFileProperty
  /** Executable accepting `--ide-path <path>`. */
  public abstract val editorDriver: RegularFileProperty
  public abstract val idePath: DirectoryProperty
  public abstract val releaseFeed: RegularFileProperty
  public abstract val ideCache: DirectoryProperty
  /** Opt in to KEFS Marketplace discovery and Android Studio compiler mapping. */
  public abstract val useKefs: Property<Boolean>
  public abstract val kefsVersion: Property<String>
  public abstract val useXvfb: Property<Boolean>
  /** JUnit XML produced by the editor test invocation to collect for each scenario. */
  public abstract val scenarioJUnitXml: RegularFileProperty
}

public class KnapshotsCompatibilityPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // JUnit discovers the editor extension when its test dependency is present.
    project.tasks.withType(Test::class.java).configureEach { task ->
      task.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    }
    val extension = project.extensions.create("knapshotsCompatibility", CompatibilityExtension::class.java)
    extension.ideCache.convention(project.layout.buildDirectory.dir("knapshots/ide-distributions"))
    extension.useKefs.convention(false)
    extension.useXvfb.convention(false)
    project.tasks.register("compilerCompatibility", CompilerCompatibilityTask::class.java) { task ->
      task.group = "verification"
      task.description = "Run compiler compatibility checks for selected Kotlin versions."
      task.driver.set(extension.compilerDriver)
    }
    project.tasks.register("editorCompatibility", EditorCompatibilityTask::class.java) { task ->
      task.group = "verification"
      task.description = "Run editor compatibility checks for an Android Studio release."
      task.driver.set(extension.editorDriver)
      task.idePath.set(extension.idePath)
      task.releaseFeed.set(extension.releaseFeed)
      task.ideCache.set(extension.ideCache)
      task.useKefs.set(extension.useKefs)
      task.kefsVersion.set(extension.kefsVersion)
      task.useXvfb.set(extension.useXvfb)
      task.reportFile.set(project.layout.buildDirectory.file("reports/knapshots/editor-compatibility.json"))
    }
    project.tasks.register("collectEditorScenario", CollectEditorScenarioTask::class.java) { task ->
      task.group = "verification"
      task.description = "Collect and validate a JUnit result for an editor scenario."
      task.junitXml.set(extension.scenarioJUnitXml)
      task.outputDirectory.set(project.layout.buildDirectory.dir("reports/knapshots/scenarios"))
    }
  }
}

@DisableCachingByDefault(because = "Compatibility drivers execute external regression suites")
public abstract class CompilerCompatibilityTask : DefaultTask() {
  init { outputs.upToDateWhen { false } }

  @get:Input
  public abstract val kotlinVersions: ListProperty<String>

  @get:InputFile
  @get:Optional
  public abstract val driver: RegularFileProperty

  @Option(option = "kotlin-version", description = "Kotlin version or dev-family selector; may be repeated")
  public fun kotlinVersion(values: List<String>) { kotlinVersions.addAll(values) }

  @TaskAction
  public fun runCompatibility() {
    val selectors = kotlinVersions.getOrElse(emptyList())
    if (selectors.isEmpty()) throw GradleException("Specify at least one --kotlin-version.")
    val executable = driver.orNull?.asFile ?: throw GradleException(
      "Configure knapshotsCompatibility.compilerDriver with a regression driver executable.",
    )
    if (!Files.isExecutable(executable.toPath())) throw GradleException("Compiler compatibility driver is not executable: $executable")
    val report = CompilerCompatibility(driver = CompilerCompatibilityDriver { version ->
      val process = ProcessBuilder(executable.absolutePath, "--kotlin-version", version)
        .directory(executable.parentFile).redirectErrorStream(true).start()
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle(it) } }
      val status = if (process.waitFor() == 0) CompatibilityStatus.PASSED else CompatibilityStatus.FAILED
      CompatibilityResult(mapOf("driver" to status))
    }).run(selectors)
    report.selections.forEach { logger.lifecycle("Kotlin selector ${it.selector} -> ${it.version}") }
    report.entries.forEach { logger.lifecycle("Kotlin ${it.version}: ${it.result.checks}") }
    if (!report.successful) throw GradleException("Compiler compatibility checks failed.")
  }
}
