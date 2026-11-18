package dev.knapshots.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.testing.Test
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/** The Gradle plugin under test. */
public abstract class TargetPlugin {
  public abstract val id: Property<String>
  public abstract val version: Property<String>
}

/** Optional Kotlin External FIR Support configuration. */
public abstract class KefsConfiguration {
  public abstract val coordinate: Property<String>
  public abstract val repository: DirectoryProperty
  public abstract val archive: RegularFileProperty
  public abstract val version: Property<String>
  public abstract val enabled: Property<Boolean>
}

/** Project-wide settings for testing a Kotlin compiler plugin. */
public abstract class KnapshotsExtension @Inject constructor(objects: ObjectFactory) {
  public val plugin: TargetPlugin = objects.newInstance(TargetPlugin::class.java)
  public val kefs: KefsConfiguration = objects.newInstance(KefsConfiguration::class.java)
  public fun plugin(action: Action<TargetPlugin>) { action.execute(plugin) }
  public fun kefs(action: Action<KefsConfiguration>) { action.execute(kefs) }
  public abstract val runnerRoot: DirectoryProperty
  public abstract val wrapperRoot: DirectoryProperty
  public abstract val adapterArchive: RegularFileProperty
  public abstract val idePath: DirectoryProperty
  public abstract val repository: DirectoryProperty
  public abstract val kotlinVersion: Property<String>
  public abstract val dependencies: ListProperty<String>
}

internal class EditorProjectArguments(
  @get:Internal val config: KnapshotsExtension,
  @get:Internal val testTask: Test,
) : CommandLineArgumentProvider {
  @get:Classpath val testClasspath: FileCollection get() = testTask.classpath

  override fun asArguments(): Iterable<String> {
    val values = mapOf(
      "runnerRoot" to config.runnerRoot.orNull?.asFile?.absolutePath,
      "wrapperRoot" to config.wrapperRoot.orNull?.asFile?.absolutePath,
      "adapterArchive" to config.adapterArchive.orNull?.asFile?.absolutePath,
      "kefsArchive" to config.kefs.archive.orNull?.asFile?.absolutePath,
      "idePath" to config.idePath.orNull?.asFile?.absolutePath,
      "repository" to config.repository.orNull?.asFile?.absolutePath,
      "kefsRepository" to config.kefs.repository.orNull?.asFile?.absolutePath,
      "kotlinVersion" to config.kotlinVersion.orNull,
      "pluginId" to config.plugin.id.orNull,
      "pluginVersion" to config.plugin.version.orNull,
      "dependencies" to config.dependencies.orNull?.joinToString(";"),
      "kefsVersion" to config.kefs.version.orNull,
      "kefsCoordinate" to config.kefs.coordinate.orNull,
      "kefsEnabled" to config.kefs.enabled.orNull?.toString(),
    )
    val classpath = testClasspath.files.filter { it.exists() }.joinToString(java.io.File.pathSeparator) { it.absolutePath }
    return (values + ("classpath" to classpath.takeIf { it.isNotEmpty() })).mapNotNull { (key, value) -> value?.let { "-Dknapshots.editor.$key=$it" } }
  }
}
