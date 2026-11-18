package dev.knapshots.testing.ksp

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class KspDiagnosticsTest {
  @Test fun `parses processor and compiler diagnostics with locations`() {
    val diagnostics = parseKspDiagnostics("""
      e: [ksp] /tmp/project/src/main/kotlin/Main.kt:4: Invalid annotation
      w: file:///tmp/project/src/main/kotlin/Main.kt:7:12: A warning
      e: [ksp] An unlocated error
    """.trimIndent(), Path.of("/tmp/project"))
    assertEquals(listOf(
      KspDiagnostic(KspDiagnosticSeverity.ERROR, "Invalid annotation", "src/main/kotlin/Main.kt", 4),
      KspDiagnostic(KspDiagnosticSeverity.WARNING, "A warning", "src/main/kotlin/Main.kt", 7, 12),
      KspDiagnostic(KspDiagnosticSeverity.ERROR, "An unlocated error"),
    ), diagnostics)
  }

  @Test fun `matches overlapping expectations and rejects unexpected errors`() {
    val host = KspHost { KspResult(false, emptyMap(), diagnostics = listOf(
      KspDiagnostic(KspDiagnosticSeverity.ERROR, "invalid name"),
      KspDiagnostic(KspDiagnosticSeverity.ERROR, "invalid type"),
    )) }
    assertKsp("source", "2.4.21", host, succeeds = false, expectedDiagnostics = listOf(
      KspDiagnosticExpectation(KspDiagnosticSeverity.ERROR, "invalid"),
      KspDiagnosticExpectation(KspDiagnosticSeverity.ERROR, "invalid name"),
    ))
    assertFailsWith<AssertionError> {
      assertKsp("source", "2.4.21", host, succeeds = false, expectedDiagnostics = listOf(
        KspDiagnosticExpectation(KspDiagnosticSeverity.ERROR, "invalid name"),
      ))
    }
  }

  @Test fun `validates execution separately from compilation`() {
    val failedRun = KspHost { KspResult(true, emptyMap(), executionSucceeded = false) }
    assertKsp("source", "2.4.21", failedRun, mainClass = "ExampleKt", executionSucceeds = false)
    assertFailsWith<AssertionError> { assertKsp("source", "2.4.21", failedRun, mainClass = "ExampleKt") }
    assertFailsWith<AssertionError> { assertKsp("source", "2.4.21", failedRun, succeeds = false, mainClass = "ExampleKt") }
  }
}
