package dev.knapshots.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** The Gradle plugin under test. */
public abstract class TargetPlugin {
  public abstract val id: Property<String>
  public abstract val version: Property<String>
}

/** Optional Kotlin External FIR Support configuration. */
public abstract class KefsConfiguration {
  /** Maven group and artifact of the IDE plugin loaded by KEFS. */
  public abstract val idePlugin: Property<String>
  public abstract val repository: DirectoryProperty
  public abstract val archive: RegularFileProperty
  public abstract val version: Property<String>
  public abstract val enabled: Property<Boolean>
}

/** Compiler plugin under test and its optional output capture options. */
public abstract class CompilerPluginConfiguration {
  public abstract val id: Property<String>
  public abstract val artifact: RegularFileProperty
  public abstract val firOption: Property<String>
  public abstract val irOption: Property<String>
}

/** IDE-side integration; additional delivery mechanisms can be added alongside KEFS. */
public abstract class IdePluginConfiguration @Inject constructor(objects: ObjectFactory) {
  public val kefs: KefsConfiguration = objects.newInstance(KefsConfiguration::class.java)
  public fun kefs(action: Action<KefsConfiguration>) { action.execute(kefs) }
}

/** Project-wide settings for testing a Kotlin compiler plugin. */
public abstract class KnapshotsExtension @Inject constructor(objects: ObjectFactory) {
  public val gradlePlugin: TargetPlugin = objects.newInstance(TargetPlugin::class.java)
  public val compilerPlugin: CompilerPluginConfiguration = objects.newInstance(CompilerPluginConfiguration::class.java)
  public fun compilerPlugin(action: Action<CompilerPluginConfiguration>) { action.execute(compilerPlugin) }
  public val idePlugin: IdePluginConfiguration = objects.newInstance(IdePluginConfiguration::class.java)
  public fun gradlePlugin(action: Action<TargetPlugin>) { action.execute(gradlePlugin) }
  public fun idePlugin(action: Action<IdePluginConfiguration>) { action.execute(idePlugin) }
  public abstract val runnerRoot: DirectoryProperty
  public abstract val wrapperRoot: DirectoryProperty
  public abstract val adapterArchive: RegularFileProperty
  public abstract val idePath: DirectoryProperty
  public abstract val repository: DirectoryProperty
  public abstract val kotlinVersion: Property<String>
  public abstract val dependencies: ListProperty<String>
  /** Default inline snapshot recording mode: none, missing, failed, or all. */
  public abstract val recording: Property<String>
  /** Record a compiler-version override rather than replacing the shared snapshot. */
  public abstract val recordCompilerVersion: Property<Boolean>
}

internal class EditorProjectArguments(
  config: KnapshotsExtension,
  @get:Classpath val testClasspath: FileCollection,
  @get:Classpath val recorderClasspath: FileCollection,
) : CommandLineArgumentProvider {
  // Keep only Gradle providers, not the decorated extension or its owning project.
  @get:Internal val editorValues = mapOf(
    "record" to config.recording,
    "runnerRoot" to config.runnerRoot.map { it.asFile.absolutePath },
    "wrapperRoot" to config.wrapperRoot.map { it.asFile.absolutePath },
    "adapterArchive" to config.adapterArchive.map { it.asFile.absolutePath },
    "kefsArchive" to config.idePlugin.kefs.archive.map { it.asFile.absolutePath },
    "idePath" to config.idePath.map { it.asFile.absolutePath },
    "repository" to config.repository.map { it.asFile.absolutePath },
    "kefsRepository" to config.idePlugin.kefs.repository.map { it.asFile.absolutePath },
    "kotlinVersion" to config.kotlinVersion,
    "pluginId" to config.gradlePlugin.id,
    "pluginVersion" to config.gradlePlugin.version,
    "dependencies" to config.dependencies.map { it.joinToString(";") },
    "kefsVersion" to config.idePlugin.kefs.version,
    "kefsCoordinate" to config.idePlugin.kefs.idePlugin,
    "kefsEnabled" to config.idePlugin.kefs.enabled.map { it.toString() },
  )
  @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
  val compilerPluginFile = config.compilerPlugin.artifact
  @get:Internal val compilerValues = mapOf(
    "pluginJar" to config.compilerPlugin.artifact.map { it.asFile.absolutePath },
    "pluginId" to config.compilerPlugin.id,
    "firOption" to config.compilerPlugin.firOption,
    "irOption" to config.compilerPlugin.irOption,
    "record" to config.recording,
    "recordVersion" to config.recordCompilerVersion.map { it.toString() },
  )
  @get:Internal val kspValues = mapOf(
    "record" to config.recording,
    "recordVersion" to config.recordCompilerVersion.map { it.toString() },
  )
  @get:Input val configurationArguments: List<String> get() =
    arguments("editor", editorValues) + arguments("compiler", compilerValues) + arguments("ksp", kspValues)

  private fun arguments(prefix: String, values: Map<String, org.gradle.api.provider.Provider<String>>): List<String> =
    values.mapNotNull { (key, provider) -> provider.orNull?.let { "-Dknapshots.$prefix.$key=$it" } }

  override fun asArguments(): List<String> {
    val classpath = testClasspath.files.filter { it.exists() }.joinToString(java.io.File.pathSeparator) { it.absolutePath }
    val classpathArgs = if (classpath.isEmpty()) emptyList() else listOf(
      "-Dknapshots.editor.classpath=$classpath", "-Dknapshots.compiler.classpath=$classpath",
    )
    val recorderArgs = if (recorderClasspath.files.isNotEmpty()) listOf(
      "-Dknapshots.editor.recorderClasspath=" + recorderClasspath.files.joinToString(java.io.File.pathSeparator) { it.absolutePath },
    ) else emptyList()
    return configurationArguments + classpathArgs + recorderArgs
  }
}
