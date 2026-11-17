package dev.knapshots.testing.compiler

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class CompilerAssertionsTest {
  @Test fun `passes sources requested captures and compiler version to its host`() {
    var request: CompilerRequest? = null
    val compiler = CompilerHost {
      request = it
      CompilerResult(
        succeeded = true,
        captures = mapOf(CompilerSnapshotKind.FIR to "generated FIR"),
      )
    }

    assertCompiler("  class Example", "2.4.21", compiler) {
      succeeds()
      fir("generated FIR")
    }

    assertEquals("class Example", request?.sources?.single()?.text)
    assertEquals("2.4.21", request?.compilerVersion)
    assertEquals(setOf(CompilerSnapshotKind.FIR), request?.captures)
  }

  @Test fun `expected failure rejects an unexpectedly successful compilation`() {
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class Example", "2.4.21", CompilerHost { CompilerResult(succeeded = true) }) {
        fails()
      }
    }
    assertContains(failure.message.orEmpty(), "Expected compilation to fail, but it succeeded")
  }

  @Test fun `expected diagnostics use one to one matching and preserve unrelated warnings`() {
    assertCompiler("class Example", "2.4.21", CompilerHost {
      CompilerResult(
        succeeded = false,
        diagnostics = listOf(
          CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "Cannot resolve Example", "Example.kt:1"),
          CompilerDiagnostic(CompilerDiagnosticSeverity.WARNING, "Unrelated warning"),
        ),
      )
    }) {
      fails()
      diagnostics { error("Cannot resolve", location = "Example.kt:1") }
    }
  }

  @Test fun `unexpected errors and missing captures do not pass silently`() {
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class Example", "2.4.21", CompilerHost {
        CompilerResult(
          succeeded = true,
          diagnostics = listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "Unexpected error")),
        )
      }) {
        succeeds()
        ir("expected IR")
      }
    }
    assertContains(failure.message.orEmpty(), "Unexpected ERROR: Unexpected error")
    assertContains(failure.message.orEmpty(), "did not provide the requested IR snapshot")
  }

  @Test fun `versioned snapshots and multiple sources are supported`() {
    assertCompiler(
      sources = listOf(
        CompilerSource("First.kt", "class First"),
        CompilerSource("Second.kt", "class Second"),
      ),
      compilerVersion = "2.5.0-dev-42",
      compiler = CompilerHost {
        assertEquals(2, it.sources.size)
        CompilerResult(succeeded = true, captures = mapOf(CompilerSnapshotKind.IR to "preview IR"))
      },
    ) {
      succeeds()
      ir(SnapshotExpectation("released IR", versions = mapOf("2.5.*" to "preview IR")))
    }
  }

  @Test fun `an assertion must state its compilation outcome`() {
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class Example", "2.4.21", CompilerHost { CompilerResult(succeeded = true) }) {}
    }
    assertContains(failure.message.orEmpty(), "Specify whether compilation succeeds or fails")
  }
}
