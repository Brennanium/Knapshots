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
}
