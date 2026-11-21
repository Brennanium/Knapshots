package dev.knapshots.fixture

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** Test-only Gradle integration for the fixture compiler plugin. */
class FixtureGradlePlugin : KotlinCompilerPluginSupportPlugin {
  override fun apply(target: Project) = Unit
  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>) = true
  override fun getCompilerPluginId() = "dev.knapshots.fixture"
  override fun getPluginArtifact() = SubpluginArtifact("dev.knapshots.testing", "fixture-compiler", "1.0.0")
  override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
    kotlinCompilation.target.project.provider { emptyList() }
}
