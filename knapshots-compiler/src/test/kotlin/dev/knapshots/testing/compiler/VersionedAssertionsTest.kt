package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VersionedAssertionsTest {
  private fun host(expected: Set<CompilerSnapshotKind>, succeeded: Boolean = true) = CompilerHost { request ->
    assertEquals(expected, request.captures)
    CompilerResult(succeeded, captures = request.captures.associateWith { kind -> "${kind.name} ${request.compilerVersion}" })
  }

  @Test fun `versioned snapshot selects the matching value or default`() {
    for (version in listOf("2.4.21", "2.5.0-dev-42")) {
      assertCompiler("class A", version, host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.NONE) {
        fir { default("FIR 2.4.21"); versioned("2.5.*", "FIR 2.5.0-dev-42"); versioned("2.6.*") }
      }
    }
  }

  @Test fun `top level versioned branches override each expectation independently`() {
    val expected = setOf(CompilerSnapshotKind.IR, CompilerSnapshotKind.FIR)
    assertCompiler("class A", "2.5.0", host(expected), record = SnapshotRecording.NONE) {
      ir("IR 2.5.0")
      default { fails() }
      versioned("2.5.*") { succeeds(); fir("FIR 2.5.0") }
    }
    assertCompiler("class A", "2.4.21", host(setOf(CompilerSnapshotKind.IR), false), record = SnapshotRecording.NONE) {
      ir("IR 2.4.21")
      default { fails() }
      versioned("2.5.*") { succeeds(); fir("ignored") }
    }
  }

  @Test fun `duplicate defaults and ambiguous selectors fail before compilation`() {
    val never = CompilerHost { error("must not compile") }
    assertFailsWith<IllegalStateException> {
      assertCompiler("", "2.4.21", never, record = SnapshotRecording.NONE) { ir("first"); default { ir("second") } }
    }
    assertFailsWith<IllegalArgumentException> {
      assertCompiler("", "2.5.0-dev-42", never, record = SnapshotRecording.NONE) {
        fir { default("x"); versioned("2.5.*", "y"); versioned(">=2.5.0-dev-1", "z") }
      }
    }
    assertFailsWith<IllegalArgumentException> {
      assertCompiler("", "2.4.21", never, record = SnapshotRecording.NONE) {
        fir { default("x"); versioned("2.5.x", "y") }
      }
    }
  }

  @Test fun `selected version placeholder records without touching inactive placeholders`(@TempDir directory: Path) {
    val file = directory.resolve("Versioned.kt")
    Files.writeString(file, "fun test() {\n  fir {\n    default()\n    versioned(\"2.5.*\")\n    versioned(\"2.6.*\")\n  }\n}\n")
    val error = assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.5.0-dev-42", host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.MISSING) {
        fir { default(location = InlineSnapshotLocation(file, 3)); versioned("2.5.*", location = InlineSnapshotLocation(file, 4)); versioned("2.6.*") }
      }
    }
    assertContains(error.message.orEmpty(), "Recorded")
    val rewritten = Files.readString(file)
    assertContains(rewritten, "default()")
    assertContains(rewritten, "versioned(\"2.6.*\")")
    assertContains(rewritten, "versioned(\"2.5.*\", \"\"\"")
    assertContains(rewritten, "FIR 2.5.0-dev-42")
  }

  @Test fun `versioned diagnostics select matching snapshot`() {
    val compiler = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
    assertCompiler("invalid", "2.5.0", compiler, record = SnapshotRecording.NONE) {
      diagnostics { default("wrong"); versioned("2.5.*", "invalid\n^\nerror: bad") }
    }
  }
  @Test fun `overridden defaults and unmatched branches never resolve their recording locations`(@TempDir directory: Path) {
    val nonexistent = InlineSnapshotLocation(directory.resolve("missing.kt"), 1)
    assertCompiler("class A", "2.5.0", host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.MISSING) {
      fir(location = nonexistent)
      versioned("2.5.*") { fir("FIR 2.5.0") }
      versioned("2.6.*") { error("inactive branch must not execute") }
    }
  }

  @Test fun `records default diagnostics without touching inactive version`(@TempDir directory: Path) {
    val file = directory.resolve("Diagnostics.kt")
    Files.writeString(file, "fun test() {\n  diagnostics {\n    default()\n    versioned(\"2.5.*\")\n  }\n}\n")
    val compiler = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Main.kt:1", 1))) }
    assertFailsWith<AssertionError> {
      assertCompiler("invalid", "2.4.21", compiler, record = SnapshotRecording.MISSING) {
        diagnostics { default(location = InlineSnapshotLocation(file, 3)); versioned("2.5.*") }
      }
    }
    val output = Files.readString(file)
    assertContains(output, "default(\"\"\"")
    assertContains(output, "error: bad")
    assertContains(output, "versioned(\"2.5.*\")")
  }

  @Test fun `recording a selector preserves named location in source`(@TempDir directory: Path) {
    val file = directory.resolve("Located.kt")
    Files.writeString(file, "fun test() {\n  versioned(\"2.5.*\", location = location)\n}\n")
    recordInlineSnapshots(InlineSnapshotLocation(file, 2), "versioned", mapOf("expected" to "result"), "2.5.0")
    val result = Files.readString(file)
    assertContains(result, "versioned(\"2.5.*\", expected = \"\"\"")
    assertContains(result, "location = location")
  }

  @Test fun `versioned recording rejects edits made during compilation`(@TempDir directory: Path) {
    val file = directory.resolve("Changed.kt")
    val source = "fun test() {\n  versioned(\"2.5.*\")\n}\n"
    Files.writeString(file, source)
    val compiler = CompilerHost { request ->
      Files.writeString(file, source + "// external edit\n")
      CompilerResult(true, captures = mapOf(CompilerSnapshotKind.FIR to "output"))
    }
    assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.5.0", compiler, record = SnapshotRecording.MISSING) {
        fir { default("old"); versioned("2.5.*", location = InlineSnapshotLocation(file, 2)) }
      }
    }
    assertEquals(source + "// external edit\n", Files.readString(file))
  }

  @Test fun `successive compiler versions record separate placeholders and compiled output`(@TempDir directory: Path) {
    val file = directory.resolve("Both.kt")
    Files.writeString(file, "fun test() {\n  fir {\n    default()\n    versioned(\"2.5.*\")\n  }\n}\n")
    for (version in listOf("2.4.21", "2.5.0")) {
      assertFailsWith<AssertionError> {
        assertCompiler("class A", version, host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.MISSING) {
          fir { default(location = InlineSnapshotLocation(file, 3)); versioned("2.5.*", location = InlineSnapshotLocation(file, 4)) }
        }
      }
    }
    val output = Files.readString(file)
    assertContains(output, "FIR 2.4.21")
    assertContains(output, "FIR 2.5.0")
    val stubs = "class Versions { fun default(value: String) {} ; fun versioned(selector: String, value: String) {} }\n" +
      "fun fir(block: Versions.() -> Unit) {}\n"
    val compiled = EmbeddedCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Both.kt", stubs + output)),
      org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION, emptySet()))
    kotlin.test.assertTrue(compiled.succeeded, compiled.diagnostics.toString())
  }

  @Test fun `existing versioned snapshots do not record just because recordVersion is enabled`() {
    assertCompiler("class A", "2.5.0", host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.MISSING, recordVersion = true) {
      fir { default("unused"); versioned("2.5.*", "FIR 2.5.0") }
    }
  }

  @Test fun `recording a named selector keeps the call valid`(@TempDir directory: Path) {
    val file = directory.resolve("Named.kt")
    Files.writeString(file, "fun test() {\n  versioned(selector = \"2.5.*\")\n}\n")
    recordInlineSnapshots(InlineSnapshotLocation(file, 2), "versioned", mapOf("expected" to "result"), "2.5.0")
    val recorded = Files.readString(file)
    assertContains(recorded, "selector = \"2.5.*\", expected = \"\"\"")
    val stub = "fun versioned(selector: String, expected: String) {}\n"
    val compiled = EmbeddedCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Named.kt", stub + recorded)),
      org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION, emptySet()))
    kotlin.test.assertTrue(compiled.succeeded, compiled.diagnostics.toString())
  }

  @Test fun `failed mode overwrites only the selected version snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("Update.kt")
    val original = "fun test() {\n  fir {\n    default(\"shared\")\n    versioned(\"2.5.*\", \"old\")\n    versioned(\"2.6.*\")\n  }\n}\n"
    Files.writeString(file, original)
    assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.5.0", host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.FAILED) {
        fir { default("shared"); versioned("2.5.*", "old", InlineSnapshotLocation(file, 4)); versioned("2.6.*") }
      }
    }
    val updated = Files.readString(file)
    assertContains(updated, "default(\"shared\")")
    assertContains(updated, "FIR 2.5.0")
    assertContains(updated, "versioned(\"2.6.*\")")
  }

  @Test fun `bytecode is requested only in the selected version branch`() {
    assertCompiler("class A", "2.4.21", host(emptySet()), record = SnapshotRecording.NONE) {
      succeeds()
      versioned("2.5.*") { bytecode("BYTECODE 2.5.0") }
    }
    assertCompiler("class A", "2.5.0", host(setOf(CompilerSnapshotKind.BYTECODE)), record = SnapshotRecording.NONE) {
      succeeds()
      versioned("2.5.*") { bytecode("BYTECODE 2.5.0") }
    }
  }

  @Test fun `missing selected placeholder fails with recording disabled`() {
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.5.0", host(setOf(CompilerSnapshotKind.FIR)), record = SnapshotRecording.NONE) {
        fir { default("shared"); versioned("2.5.*") }
      }
    }
    assertContains(failure.message.orEmpty(), "Missing FIR snapshot")
  }

}
