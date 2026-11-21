package dev.knapshots.testing.compiler

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
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

  @Test fun `snapshot failure shows a focused diff`() {
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class Example", "2.4.21", CompilerHost {
        CompilerResult(succeeded = true, captures = mapOf(CompilerSnapshotKind.FIR to "first\nactual\nlast"))
      }) { fir("first\nexpected\nlast") }
    }
    assertContains(failure.message.orEmpty(), "− expected\n+ actual")
    assertContains(failure.message.orEmpty(), "@@ -1,3 +1,3 @@")
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

  @Test fun `diagnostic recording modes distinguish missing equal and changed snapshots`(@TempDir directory: Path) {
    val actual = "invalid\n^\nerror: bad"
    val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
    val cases = listOf(
      Triple(SnapshotRecording.NONE, null, false), Triple(SnapshotRecording.MISSING, null, true),
      Triple(SnapshotRecording.MISSING, "old", false), Triple(SnapshotRecording.MISSING, actual, false),
      Triple(SnapshotRecording.FAILED, actual, false), Triple(SnapshotRecording.FAILED, "old", true),
      Triple(SnapshotRecording.FAILED, null, true), Triple(SnapshotRecording.ALL, actual, true),
    )
    cases.forEachIndexed { index, (mode, expected, changes) ->
      val file = directory.resolve("Mode$index.kt")
      val original = "fun test() {\n  diagnostics { snapshot(\"placeholder\") }\n}\n"
      Files.writeString(file, original)
      val run = { assertCompiler("invalid", "2.4.21", host, record = mode) {
        diagnostics { snapshot(expected, InlineSnapshotLocation(file, 2)) }
      } }
      val shouldPass = !changes && expected == actual
      if (shouldPass) run() else assertFailsWith<AssertionError> { run() }
      if (changes) assertContains(Files.readString(file), "error: bad") else assertEquals(original, Files.readString(file))
    }
  }

  @Test fun `input only recording inserts diagnostics for failed and clean compilations`(@TempDir directory: Path) {
    val errorFile = directory.resolve("Error.kt")
    Files.writeString(errorFile, "fun test() {\n  assertCompiler(\"invalid\")\n}\n")
    val failed = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
    assertFailsWith<AssertionError> {
      assertCompiler("invalid", "2.4.21", failed, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(errorFile, 2))
    }
    assertContains(Files.readString(errorFile), "assertCompiler(\"invalid\") { diagnostics { snapshot(")
    assertContains(Files.readString(errorFile), "error: bad")
    val cleanFile = directory.resolve("Clean.kt")
    Files.writeString(cleanFile, "fun test() {\n  assertCompiler(\"valid\")\n}\n")
    assertFailsWith<AssertionError> {
      assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(true) }, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(cleanFile, 2))
    }
    assertContains(Files.readString(cleanFile), "diagnostics { snapshot(")
    assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(true) }, record = SnapshotRecording.NONE) { diagnostics { snapshot("") } }
  }

  @Test fun `missing mode compares existing diagnostics without locating test source`(@TempDir directory: Path) {
    val previous = System.getProperty("user.dir")
    try {
      System.setProperty("user.dir", directory.toString())
      val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
      assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.MISSING) {
        diagnostics { snapshot("invalid\n^\nerror: bad") }
      }
    } finally { System.setProperty("user.dir", previous) }
  }

  @Test fun `input only defaults to recording missing diagnostics`(@TempDir directory: Path) {
    val file = directory.resolve("Default.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"valid\")\n}\n")
    val previous = System.getProperty("knapshots.compiler.record")
    try {
      System.clearProperty("knapshots.compiler.record")
      assertFailsWith<AssertionError> {
        assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(true) }, location = InlineSnapshotLocation(file, 2))
      }
      assertContains(Files.readString(file), "diagnostics { snapshot(")
    } finally {
      if (previous == null) System.clearProperty("knapshots.compiler.record") else System.setProperty("knapshots.compiler.record", previous)
    }
  }

  @Test fun `input recording appends diagnostics to an existing block`(@TempDir directory: Path) {
    val file = directory.resolve("Block.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"invalid\") {\n    // preserve this comment\n  }\n}\n")
    val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
    assertFailsWith<AssertionError> {
      assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2)) {}
    }
    assertContains(Files.readString(file), "// preserve this comment")
    assertContains(Files.readString(file), "diagnostics { snapshot(")
  }

  @Test fun `explicit output and diagnostics do not require a successful compilation`() {
    val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1)), mapOf(CompilerSnapshotKind.FIR to "partial FIR")) }
    val diagnostics = "invalid\n^\nerror: bad"
    assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.NONE) {
      fir("partial FIR")
      diagnostics { snapshot(diagnostics) }
    }
    assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.NONE) { fir("partial FIR") }
    assertCompiler("invalid", "2.4.21", host, fir = SnapshotExpectation("partial FIR"),
      diagnostics = SnapshotExpectation(diagnostics), record = SnapshotRecording.NONE)
    assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.MISSING) { fails() }
  }

  @Test fun `failed compilations may omit unavailable default phase captures`(@TempDir directory: Path) {
    val file = directory.resolve("Failed.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"invalid\")\n}\n")
    val host = object : CompilerHost {
      override fun defaultSnapshotKinds() = setOf(CompilerSnapshotKind.IR)
      override fun compile(request: CompilerRequest) = CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1)))
    }
    assertFailsWith<AssertionError> {
      assertCompiler("invalid", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertContains(Files.readString(file), "error: bad")
    check(!Files.readString(file).contains("ir("))
  }

  @Test fun `missing default captures from successful compilation leave source unchanged`(@TempDir directory: Path) {
    val file = directory.resolve("BrokenHost.kt")
    val original = "fun test() {\n  assertCompiler(\"valid\")\n}\n"
    Files.writeString(file, original)
    val host = object : CompilerHost {
      override fun defaultSnapshotKinds() = setOf(CompilerSnapshotKind.IR)
      override fun compile(request: CompilerRequest) = CompilerResult(true)
    }
    assertFailsWith<AssertionError> {
      assertCompiler("valid", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `input only never requests bytecode automatically`(@TempDir directory: Path) {
    val file = directory.resolve("OptIn.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"valid\")\n}\n")
    val host = object : CompilerHost {
      override fun defaultSnapshotKinds() = setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.BYTECODE)
      override fun compile(request: CompilerRequest): CompilerResult {
        assertEquals(setOf(CompilerSnapshotKind.FIR), request.captures)
        return CompilerResult(true, captures = mapOf(CompilerSnapshotKind.FIR to "FIR"))
      }
    }
    assertFailsWith<AssertionError> {
      assertCompiler("valid", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "fir(")
    check(!recorded.contains("bytecode("))
  }

  @Test fun `comparison only never writes a missing input snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("NoRecord.kt")
    val original = "fun test() {\n  assertCompiler(\"valid\")\n}\n"
    Files.writeString(file, original)
    assertFailsWith<AssertionError> {
      assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(true) }, record = SnapshotRecording.NONE, location = InlineSnapshotLocation(file, 2))
    }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `input only recording never overwrites a source edit`(@TempDir directory: Path) {
    val file = directory.resolve("Edited.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"valid\")\n}\n")
    val edit = "// changed by developer\n"
    val host = CompilerHost { Files.writeString(file, edit); CompilerResult(true) }
    assertFailsWith<AssertionError> {
      assertCompiler("valid", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertEquals(edit, Files.readString(file))
  }

  @Test fun `input only supports diagnostics in an additional source`(@TempDir directory: Path) {
    val file = directory.resolve("Multiple.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"main\")\n}\n")
    val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Other.kt:1", 1))) }
    assertFailsWith<AssertionError> {
      assertCompiler("main", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2),
        additionalSources = listOf(CompilerSource("Other.kt", "invalid")))
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "// FILE: Main.kt")
    assertContains(recorded, "// FILE: Other.kt")
    assertContains(recorded, "error: bad")
  }

  @Test fun `compilation outcome is optional but failures remain checked`() {
    assertCompiler("class Example", "2.4.21", CompilerHost { CompilerResult(succeeded = true) }) { diagnostics { snapshot("") } }
    assertCompiler("invalid", "2.4.21", CompilerHost { CompilerResult(succeeded = false, diagnostics = listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "any error"))) }) { fails() }
    assertCompiler("invalid", "2.4.21", CompilerHost { CompilerResult(succeeded = false) }, record = SnapshotRecording.MISSING) { fails() }
    assertCompiler("invalid", "2.4.21", CompilerHost { CompilerResult(succeeded = false) }, record = SnapshotRecording.NONE) { diagnostics { snapshot("") } }
    assertFailsWith<AssertionError> { assertCompiler("invalid", "2.4.21", CompilerHost { CompilerResult(succeeded = false) }) {} }
    assertFailsWith<AssertionError> { assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(succeeded = true) }) { fails() } }
    assertFailsWith<AssertionError> { assertCompiler("valid", "2.4.21", CompilerHost { CompilerResult(true) }, record = SnapshotRecording.NONE) }
  }
  @Test fun `snapshot comparison preserves carriage returns`() {
    val actual = "first\r\nsecond\rthird"
    val host = CompilerHost { CompilerResult(true, captures = mapOf(CompilerSnapshotKind.FIR to actual)) }
    assertCompiler("source", "2.4.21", host, fir = SnapshotExpectation(actual))
    assertFailsWith<AssertionError> {
      assertCompiler("source", "2.4.21", host, fir = SnapshotExpectation("first\nsecond\nthird"))
    }
  }

}
