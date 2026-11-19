package dev.knapshots.testing.compiler.gradle

import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GradleCompilerHostTest {
  @Test fun `compiles sources and returns class files`() {
    val result = GradleCompilerHost().compile(CompilerRequest(
      listOf(CompilerSource("sample/Example.kt", "package sample\nclass Example")),
      KotlinVersion.CURRENT.toString(), emptySet(),
    ))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertTrue("sample/Example.class" in result.classFiles)
  }

  @Test fun `reports compilation failure rather than success`() {
    val result = GradleCompilerHost().compile(CompilerRequest(
      listOf(CompilerSource("Example.kt", "val count: Int = \"wrong\"")),
      KotlinVersion.CURRENT.toString(), emptySet(),
    ))
    assertFalse(result.succeeded)
    assertContains(result.diagnostics.single().message, "compileKotlin")
  }

  @Test fun `rejects paths outside the source directory`() {
    assertFailsWith<IllegalArgumentException> {
      GradleCompilerHost().compile(CompilerRequest(
        listOf(CompilerSource("../Escape.kt", "class Escape")),
        KotlinVersion.CURRENT.toString(), emptySet(),
      ))
    }
  }
}
