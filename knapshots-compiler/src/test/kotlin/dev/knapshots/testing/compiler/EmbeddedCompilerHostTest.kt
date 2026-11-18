package dev.knapshots.testing.compiler

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class EmbeddedCompilerHostTest {
  private val version = KotlinCompilerVersion.VERSION
  private val host = EmbeddedCompilerHost()

  @Test fun `compiles real source`() {
    assertCompiler("class Example", version, host) { succeeds() }
  }

  @Test fun `reports real compiler errors`() {
    assertCompiler("class Example { val value: Missing = TODO() }", version, host) {
      fails()
      diagnostics { error("Unresolved reference") }
    }
  }

  @Test fun `rejects misleading version and unsupported capture`() {
    assertFailsWith<IllegalArgumentException> {
      host.compile(CompilerRequest(listOf(CompilerSource("Test.kt", "class Example")), "0.0.0", emptySet()))
    }
    assertFailsWith<IllegalArgumentException> {
      host.compile(CompilerRequest(listOf(CompilerSource("Test.kt", "class Example")), version, setOf(CompilerSnapshotKind.IR)))
    }
  }
  @Test fun `compiled JVM output remains loadable and inspectable after cleanup`() {
    val version = org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION
    val result = EmbeddedCompilerHost().compile(CompilerRequest(
      listOf(CompilerSource("Example.kt", "package fixture\nfun answer(): Int = 42")), version, emptySet(),
    ))
    kotlin.test.assertTrue(result.succeeded, result.diagnostics.toString())
    val loader = result.classLoader()
    kotlin.test.assertEquals(42, loader.loadClass("fixture.ExampleKt").getMethod("answer").invoke(null))
    kotlin.test.assertNotNull(loader.getResourceAsStream("fixture/ExampleKt.class")).use { bytes ->
      kotlin.test.assertTrue(bytes.readBytes().isNotEmpty())
    }
  }

}
