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
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
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
    val editorProject = project.extensions.create("knapshots", KnapshotsExtension::class.java)
    editorProject.gradlePlugin.id.convention(project.provider {
      project.extensions.findByType(GradlePluginDevelopmentExtension::class.java)
        ?.plugins?.mapNotNull { it.id?.takeIf(String::isNotBlank) }?.singleOrNull()
    })
    // Until KEFS can map distinct artifact names, its IDE artifact uses the build artifact ID.
    editorProject.idePlugin.kefs.idePlugin.convention(project.provider {
      editorProject.gradlePlugin.id.orNull?.let { id -> compilerPluginArtifact(project, id) }
    })
    editorProject.wrapperRoot.convention(project.rootProject.layout.projectDirectory)
    editorProject.gradlePlugin.version.convention(project.provider { project.version.toString().takeUnless { it == "unspecified" } })
    editorProject.kotlinVersion.convention(project.provider {
      project.extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")?.orElse(null)
        ?.findVersion("kotlin")?.orElse(null)?.requiredVersion?.takeIf(String::isNotBlank)
    })
    editorProject.runnerRoot.convention(project.layout.dir(project.providers.gradleProperty("knapshots.ideRunnerRoot").map(project::file)))
    editorProject.idePath.convention(project.layout.dir(
      project.providers.gradleProperty("knapshots.idePath").orElse(project.providers.environmentVariable("KNAPSHOTS_IDE_PATH")).map(project::file),
    ))
    editorProject.idePlugin.kefs.archive.convention(project.layout.file(project.providers.environmentVariable("KNAPSHOTS_KEFS_ARCHIVE").map(project::file)))
    editorProject.adapterArchive.convention(project.layout.file(project.providers.environmentVariable("KNAPSHOTS_IDE_ADAPTER_ARCHIVE").map(project::file)))
    editorProject.idePlugin.kefs.version.convention(project.providers.gradleProperty("knapshots.kefsVersion"))
    // JUnit discovers the editor extension when its test dependency is present.
    project.tasks.withType(Test::class.java).configureEach { task ->
      task.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
      task.jvmArgumentProviders.add(EditorProjectArguments(editorProject, task))
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


/** Reads metadata only from an already-applied Kotlin compiler support plugin. */
private fun compilerPluginArtifact(project: Project, id: String): String? {
  val plugin = project.plugins.findPlugin(id) ?: return null
  return compilerPluginArtifact(plugin)
}

internal fun compilerPluginArtifact(plugin: Any): String? {
  return try {
    val support = Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin", false, plugin.javaClass.classLoader)
    if (!support.isInstance(plugin)) return null
    val artifact = support.getMethod("getPluginArtifact").invoke(plugin) ?: return null
    val group = artifact.javaClass.getMethod("getGroupId").invoke(artifact) as? String
    val name = artifact.javaClass.getMethod("getArtifactId").invoke(artifact) as? String
    if (group.isNullOrBlank() || name.isNullOrBlank()) null else "$group:$name"
  } catch (_: ReflectiveOperationException) {
    null
  } catch (_: LinkageError) {
    null
  }
}
