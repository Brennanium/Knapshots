package dev.knapshots.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompilerPluginArtifactTest {
  private class ExamplePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) = Unit
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>) = true
    override fun getCompilerPluginId() = "example.compiler"
    override fun getPluginArtifact() = SubpluginArtifact("example.group", "compiler-artifact", "1.2.3")
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> = error("not used")
  }

  @Test fun `reads the compiler artifact from Kotlin plugin metadata`() {
    assertEquals("example.group:compiler-artifact", compilerPluginArtifact(ExamplePlugin()))
    assertEquals("example.compiler", compilerPluginId(ExamplePlugin()))
  }

  @Test fun `does not treat arbitrary similarly named objects as Kotlin plugins`() {
    val unrelated = object { fun getPluginArtifact() = "example:wrong" }
    assertNull(compilerPluginArtifact(unrelated))
    assertNull(compilerPluginId(unrelated))
  }
}
