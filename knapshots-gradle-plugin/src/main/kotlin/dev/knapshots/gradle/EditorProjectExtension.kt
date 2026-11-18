package dev.knapshots.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/** Project-wide settings for materializing editor-test projects and launching the IDE adapter. */
public abstract class EditorProjectExtension {
  public abstract val runnerRoot: DirectoryProperty
  public abstract val idePath: DirectoryProperty
  public abstract val repository: DirectoryProperty
  public abstract val kefsRepository: DirectoryProperty
  public abstract val kotlinVersion: Property<String>
  public abstract val pluginId: Property<String>
  public abstract val pluginVersion: Property<String>
  public abstract val dependencies: ListProperty<String>
  public abstract val kefsVersion: Property<String>
  public abstract val kefsCoordinate: Property<String>
  public abstract val kefsEnabled: Property<Boolean>
}

internal class EditorProjectArguments(@get:Internal val config: EditorProjectExtension) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val values = mapOf(
      "runnerRoot" to config.runnerRoot.orNull?.asFile?.absolutePath,
      "idePath" to config.idePath.orNull?.asFile?.absolutePath,
      "repository" to config.repository.orNull?.asFile?.absolutePath,
      "kefsRepository" to config.kefsRepository.orNull?.asFile?.absolutePath,
      "kotlinVersion" to config.kotlinVersion.orNull,
      "pluginId" to config.pluginId.orNull,
      "pluginVersion" to config.pluginVersion.orNull,
      "dependencies" to config.dependencies.orNull?.joinToString(";"),
      "kefsVersion" to config.kefsVersion.orNull,
      "kefsCoordinate" to config.kefsCoordinate.orNull,
      "kefsEnabled" to config.kefsEnabled.orNull?.toString(),
    )
    return values.mapNotNull { (key, value) -> value?.let { "-Dknapshots.editor.$key=$it" } }
  }
}
