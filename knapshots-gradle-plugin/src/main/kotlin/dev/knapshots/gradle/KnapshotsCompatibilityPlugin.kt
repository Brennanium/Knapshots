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
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import groovy.json.JsonOutput
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.io.File

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
  /** Additional environment variables passed to compatibility drivers. */
  public abstract val driverEnvironment: MapProperty<String, String>
}

public class KnapshotsCompatibilityPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val versionProperties = java.util.Properties().apply {
      KnapshotsCompatibilityPlugin::class.java.getResourceAsStream("/dev/knapshots/gradle/version.properties")!!.use(::load)
    }
    val recorder = project.configurations.create("knapshotsEditorRecorder") {
      it.isCanBeConsumed = false
      it.defaultDependencies { dependencies ->
        dependencies.add(project.dependencies.create("dev.knapshots:knapshots-compiler:${versionProperties.getProperty("version")}"))
      }
    }
    val editorProject = project.extensions.create("knapshots", KnapshotsExtension::class.java)
    editorProject.recording.convention(project.providers.gradleProperty("knapshots.recording"))
    editorProject.recordCompilerVersion.convention(project.providers.gradleProperty("knapshots.recordCompilerVersion").map { it.toBooleanStrict() })
    editorProject.wrapperRoot.convention(project.rootProject.layout.projectDirectory)
    editorProject.ksp.version.convention(versionProperties.getProperty("kspVersion"))
    // Resolve project-derived defaults after the build script configures its plugins. Store values,
    // not Project objects, in the test argument provider so configuration caching remains possible.
    project.afterEvaluate {
      project.extensions.findByType(GradlePluginDevelopmentExtension::class.java)
        ?.plugins?.mapNotNull { it.id?.takeIf(String::isNotBlank) }?.singleOrNull()
        ?.let { editorProject.gradlePlugin.id.convention(it) }
      project.version.toString().takeUnless { it == "unspecified" }
        ?.let { editorProject.gradlePlugin.version.convention(it) }
      project.extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")?.orElse(null)
        ?.findVersion("kotlin")?.orElse(null)?.requiredVersion?.takeIf(String::isNotBlank)
        ?.let { editorProject.kotlinVersion.convention(it) }
      // The applied Kotlin plugin is authoritative even when no version catalog is present.
      kotlinGradlePluginVersion(project)?.let { editorProject.kotlinVersion.convention(it) }
      project.extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")?.orElse(null)
        ?.findVersion("ksp")?.orElse(null)?.requiredVersion?.takeIf(String::isNotBlank)
        ?.let { editorProject.ksp.version.convention(it) }
      editorProject.gradlePlugin.id.orNull?.let { id ->
        compilerPluginArtifact(project, id)?.let { editorProject.idePlugin.kefs.idePlugin.convention(it) }
        project.plugins.findPlugin(id)?.let { plugin ->
          compilerPluginId(plugin)?.let { editorProject.compilerPlugin.id.convention(it) }
        }
      }
      // A compiler plugin declared in this project can be tested without spelling its JAR path.
      val compilerService = project.layout.projectDirectory.file(
        "src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
      ).asFile
      if (compilerService.isFile && project.tasks.findByName("jar") is Jar) {
        editorProject.compilerPlugin.artifact.convention(project.tasks.named("jar", Jar::class.java).flatMap { it.archiveFile })
      }
      // A standard service descriptor is unambiguous evidence that this project builds a KSP
      // processor. Prefer an explicit selection if the user already configured one.
      val processorService = project.layout.projectDirectory.file(
        "src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider",
      ).asFile
      if (processorService.isFile && editorProject.ksp.processorClasspath.from.isEmpty() && project.tasks.findByName("jar") is Jar) {
        editorProject.ksp.processorClasspath.from(project.tasks.named("jar"))
        project.configurations.findByName("runtimeClasspath")?.let { editorProject.ksp.processorClasspath.from(it) }
      }
      val editorDeclared = project.configurations.filter { it.name.endsWith("RuntimeClasspath") }.any { configuration ->
        configuration.allDependencies.any { dependency ->
          dependency.group == "dev.knapshots" && (dependency.name.startsWith("knapshots-editor") || dependency.name == "knapshots-intellij-fixture")
        }
      }
      // Gradle TestKit is distributed with Gradle, not through the published host artifacts.
      // Supply it when the consumer uses one of the Gradle-backed compiler or KSP hosts.
      project.configurations.filter { it.name.endsWith("RuntimeClasspath") }.forEach { configuration ->
        val usesGradleHost = configuration.allDependencies.any { dependency ->
          dependency.group == "dev.knapshots" && (dependency.name == "knapshots-ksp" || dependency.name == "knapshots-compiler-gradle")
        }
        val runtimeOnly = configuration.name.removeSuffix("RuntimeClasspath") + "RuntimeOnly"
        if (usesGradleHost && project.configurations.findByName(runtimeOnly) != null) {
          project.dependencies.add(runtimeOnly, project.dependencies.gradleTestKit())
        }
      }
      // A test may opt in per assertion at runtime, so make the isolated worker available even
      // when no project-wide recording mode has been selected.
      val recordingFiles = if (editorDeclared) project.files(recorder) else project.files()
      project.tasks.withType(Test::class.java).configureEach { task ->
        task.jvmArgumentProviders.add(EditorProjectArguments(editorProject, task.classpath, recordingFiles, editorProject.ksp.processorClasspath))
      }
    }
    editorProject.runnerRoot.convention(project.layout.dir(project.providers.gradleProperty("knapshots.ideRunnerRoot").orElse(project.providers.environmentVariable("KNAPSHOTS_CHECKOUT")).map(::File)))
    editorProject.idePath.convention(project.layout.dir(
      project.providers.gradleProperty("knapshots.idePath").orElse(project.providers.environmentVariable("KNAPSHOTS_IDE_PATH")).map(::File),
    ))
    editorProject.idePlugin.kefs.archive.convention(project.layout.file(project.providers.environmentVariable("KNAPSHOTS_KEFS_ARCHIVE").map(::File)))
    editorProject.adapterArchive.convention(project.layout.file(project.providers.environmentVariable("KNAPSHOTS_IDE_ADAPTER_ARCHIVE").map(::File)))
    editorProject.idePlugin.kefs.version.convention(project.providers.gradleProperty("knapshots.kefsVersion"))
    // JUnit discovers the editor extension when its test dependency is present.
    project.tasks.withType(Test::class.java).configureEach { task ->
      task.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    }
    val extension = project.extensions.create("knapshotsCompatibility", CompatibilityExtension::class.java)
    extension.ideCache.convention(project.layout.buildDirectory.dir("knapshots/ide-distributions"))
    extension.useKefs.convention(false)
    extension.useXvfb.convention(false)
    extension.driverEnvironment.convention(emptyMap())
    project.tasks.register("compilerCompatibility", CompilerCompatibilityTask::class.java) { task ->
      task.group = "verification"
      task.description = "Run compiler compatibility checks for selected Kotlin versions."
      task.driver.set(extension.compilerDriver)
      task.driverEnvironment.set(extension.driverEnvironment)
      task.reportFile.set(project.layout.buildDirectory.file("reports/knapshots/compiler-compatibility.json"))
    }
    project.tasks.register("editorCompatibility", EditorCompatibilityTask::class.java) { task ->
      task.group = "verification"
      task.description = "Run editor compatibility checks for an Android Studio release."
      task.driver.set(extension.editorDriver)
      task.driverEnvironment.set(extension.driverEnvironment)
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

internal fun splitKotlinVersionSelectors(values: List<String>): List<String> = values.flatMap { value ->
  value.split(',').map { selector ->
    selector.trim().also { require(it.isNotEmpty()) { "Empty Kotlin version selector in: $value" } }
  }
}

@DisableCachingByDefault(because = "Compatibility drivers execute external regression suites")
public abstract class CompilerCompatibilityTask : DefaultTask() {
  init { outputs.upToDateWhen { false } }

  @get:Input
  public abstract val kotlinVersions: ListProperty<String>

  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract val driver: RegularFileProperty

  @get:Input public abstract val platforms: ListProperty<String>
  @get:Input public abstract val driverEnvironment: MapProperty<String, String>
  @get:OutputFile public abstract val reportFile: RegularFileProperty

  @Option(option = "platform", description = "Comma-separated target platforms, passed to the regression driver")
  public fun platform(value: String) {
    val selected = value.split(',').map(String::trim)
    require(selected.isNotEmpty() && selected.all { it.matches(Regex("[A-Za-z0-9_-]+")) } && selected.distinct().size == selected.size) { "Invalid platform list: $value" }
    platforms.set(selected)
  }

  @Option(option = "kotlin-version", description = "Comma-separated Kotlin versions or dev-family selectors; may be repeated")
  public fun kotlinVersion(values: List<String>) { kotlinVersions.addAll(splitKotlinVersionSelectors(values)) }

  @TaskAction
  public fun runCompatibility() {
    val selectors = kotlinVersions.getOrElse(emptyList())
    if (selectors.isEmpty()) throw GradleException("Specify at least one --kotlin-version.")
    val executable = driver.orNull?.asFile ?: throw GradleException(
      "Configure knapshotsCompatibility.compilerDriver with a regression driver executable.",
    )
    if (!Files.isExecutable(executable.toPath())) throw GradleException("Compiler compatibility driver is not executable: $executable")
    val report = CompilerCompatibility(driver = CompilerCompatibilityDriver { version ->
      val args = mutableListOf(executable.absolutePath, "--kotlin-version", version)
      platforms.getOrElse(emptyList()).takeIf { it.isNotEmpty() }?.let { args.addAll(listOf("--platform", it.joinToString(","))) }
      val builder = ProcessBuilder(args).directory(executable.parentFile).redirectErrorStream(true)
      builder.environment().putAll(driverEnvironment.get())
      val process = builder.start()
      val checks = linkedMapOf<String, CompatibilityStatus>()
      val marker = Regex("^KNAPSHOTS_CHECK ([A-Za-z0-9_-]+) (passed|failed|not-run)$")
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
        logger.lifecycle(line)
        marker.matchEntire(line)?.let { match ->
          val status = when (match.groupValues[2]) { "passed" -> CompatibilityStatus.PASSED; "failed" -> CompatibilityStatus.FAILED; else -> CompatibilityStatus.NOT_RUN }
          check(checks.put(match.groupValues[1], status) == null) { "Duplicate compatibility check: ${match.groupValues[1]}" }
        }
      } }
      val exit = process.waitFor()
      if (checks.isEmpty() || exit != 0) checks["driver"] = if (exit == 0) CompatibilityStatus.PASSED else CompatibilityStatus.FAILED
      CompatibilityResult(checks)
    }).run(selectors)
    report.selections.forEach { logger.lifecycle("Kotlin selector ${it.selector} -> ${it.version}") }
    report.entries.forEach { logger.lifecycle("Kotlin ${it.version}: ${it.result.checks}") }
    val output = reportFile.get().asFile.toPath()
    Files.createDirectories(output.parent)
    Files.writeString(output, JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
      "selections" to report.selections.map { mapOf("selector" to it.selector, "version" to it.version) },
      "entries" to report.entries.map { entry -> mapOf("version" to entry.version, "checks" to entry.result.checks.mapValues { it.value.name.lowercase() }, "detail" to entry.result.detail) },
      "successful" to report.successful,
    ))) + "\n")
    if (!report.successful) throw GradleException("Compiler compatibility checks failed.")
  }
}


/** Read the actual applied Kotlin plugin version without requiring a version catalog. */
internal fun kotlinGradlePluginVersion(project: Project): String? {
  val plugin = project.plugins.findPlugin("org.jetbrains.kotlin.jvm") ?: return null
  return try {
    val wrapper = Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt", false, plugin.javaClass.classLoader)
    (wrapper.getMethod("getKotlinPluginVersion", Project::class.java).invoke(null, project) as? String)?.takeIf(String::isNotBlank)
  } catch (_: ReflectiveOperationException) {
    null
  } catch (_: LinkageError) {
    null
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

/** Reads the compiler plugin ID from an already-applied Kotlin Gradle integration. */
internal fun compilerPluginId(plugin: Any): String? = try {
  val support = Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin", false, plugin.javaClass.classLoader)
  if (!support.isInstance(plugin)) null
  else (support.getMethod("getCompilerPluginId").invoke(plugin) as? String)?.takeIf(String::isNotBlank)
} catch (_: ReflectiveOperationException) {
  null
} catch (_: LinkageError) {
  null
}
