package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerDiagnosticRenderingTest {
  @Test fun `renders multiple diagnostics, tabs, other files and missing locations`() {
    val sources = listOf(CompilerSource("Main.kt", "\tval x = 1"), CompilerSource("Other.kt", "val y = 2"))
    val diagnostics = listOf(
      CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "first", "Main.kt:1", 6),
      CompilerDiagnostic(CompilerDiagnosticSeverity.WARNING, "second", "Main.kt:1", 2),
      CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "elsewhere", "Other.kt:1", 1),
      CompilerDiagnostic(CompilerDiagnosticSeverity.WARNING, "no position"),
    )
    assertEquals("// FILE: Main.kt\n\tval x = 1\n\t    ^\nerror: first\n\t^\nwarning: second\n// FILE: Other.kt\nval y = 2\n^\nerror: elsewhere\nwarning: no position", renderCompilerDiagnostics(sources, diagnostics))
    assertEquals("", renderCompilerDiagnostics(sources, emptyList()))
  }

  @Test fun `renders available same-line ranges and falls back for invalid ranges`() {
    val source = CompilerSource("Main.kt", "\tval name = missingName")
    val at = source.text.indexOf("missingName") + 1
    val diagnostics = listOf(
      CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "missing", "Main.kt:1", at, 1, at + 11),
      CompilerDiagnostic(CompilerDiagnosticSeverity.WARNING, "invalid", "Main.kt:1", at, 1, at - 1),
      CompilerDiagnostic(CompilerDiagnosticSeverity.WARNING, "multiline", "Main.kt:1", at, 2, 3),
    )
    val rendered = renderCompilerDiagnostics(listOf(source), diagnostics)
    assertContains(rendered, "^~~~~~~~~~~\nerror: missing")
    assertContains(rendered, "^\nwarning: invalid")
    assertContains(rendered, "^\nwarning: multiline")
  }

  @Test fun `embedded compiler exposes its available diagnostic end positions`() {
    val source = "val count = unknownIdentifier"
    val result = EmbeddedCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Main.kt", source)), KotlinCompilerVersion.VERSION, emptySet()))
    val error = result.diagnostics.first { it.severity == CompilerDiagnosticSeverity.ERROR }
    assertEquals(1, error.location?.substringAfterLast(':')?.toIntOrNull())
    assertEquals(source.indexOf("unknownIdentifier") + 1, error.column)
    assertEquals(1, error.endLine)
    assertEquals(source.length + 1, error.endColumn)
    assertContains(renderCompilerDiagnostics(listOf(CompilerSource("Main.kt", source)), result.diagnostics), "^~~~~~~~~~~~~~~~~\nerror:")
  }

  @Test fun `records real failed compilation and then compares snapshot`(@TempDir directory: Path) {
    val source = "val count: Int = \"hello\""
    val file = directory.resolve("Test.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(source = \"example\", diagnostics = snapshot())\n}\n")
    val host = EmbeddedCompilerHost()
    val version = KotlinCompilerVersion.VERSION
    val failure = assertFailsWith<AssertionError> {
      assertCompiler(source, version, host, diagnostics = snapshot(), record = SnapshotRecording.MISSING,
        location = InlineSnapshotLocation(file, 2))
    }
    assertContains(failure.message.orEmpty(), "Recorded inline snapshots")
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Main.kt", source)), version, emptySet()))
    val output = renderCompilerDiagnostics(listOf(CompilerSource("Main.kt", source)), result.diagnostics)
    assertContains(Files.readString(file), "diagnostics = SnapshotExpectation(")
    assertContains(Files.readString(file), "error:")
    assertCompiler(source, version, host, diagnostics = SnapshotExpectation(output))
    assertFailsWith<AssertionError> { assertCompiler(source, version, host, diagnostics = SnapshotExpectation("")) }
  }

  @Test fun `diagnostic block records and compares a compiler error`(@TempDir directory: Path) {
    val file = directory.resolve("Test.kt")
    Files.writeString(file, "fun test() {\n  diagnostics { snapshot() }\n}\n")
    val source = "val count: Int = \"hello\""
    val host = EmbeddedCompilerHost()
    val version = KotlinCompilerVersion.VERSION
    val failure = assertFailsWith<AssertionError> {
      assertCompiler(source, version, host, record = SnapshotRecording.MISSING) {
        fails()
        diagnostics { snapshot(location = InlineSnapshotLocation(file, 2)) }
      }
    }
    assertContains(failure.message.orEmpty(), "Recorded diagnostics")
    assertContains(Files.readString(file), "snapshot(")
    assertContains(Files.readString(file), "error:")
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Main.kt", source)), version, emptySet()))
    val output = renderCompilerDiagnostics(listOf(CompilerSource("Main.kt", source)), result.diagnostics)
    assertCompiler(source, version, host) { diagnostics { snapshot(output) } }
    assertCompiler(source, version, host) { fails(); diagnostics { snapshot(SnapshotExpectation("wrong", versions = mapOf(version to output))) } }
    assertFailsWith<AssertionError> { assertCompiler(source, version, host) { succeeds(); diagnostics { snapshot(output) } } }
  }

  @Test fun `diagnostics shorthand records an explicit block`(@TempDir directory: Path) {
    val file = directory.resolve("Short.kt")
    Files.writeString(file, "fun test() {\n  diagnostics()\n}\n")
    val error = assertFailsWith<AssertionError> {
      assertCompiler("val count: Int = \"hello\"", KotlinCompilerVersion.VERSION, EmbeddedCompilerHost(), record = SnapshotRecording.MISSING) {
        fails(); diagnostics(InlineSnapshotLocation(file, 2))
      }
    }
    assertContains(error.message.orEmpty(), "Recorded diagnostics")
    assertContains(Files.readString(file), "diagnostics { snapshot(")
  }

  @Test fun `diagnostic block records a compiler version override`(@TempDir directory: Path) {
    val file = directory.resolve("Versioned.kt")
    Files.writeString(file, "fun test() {\n  diagnostics { snapshot(SnapshotExpectation(\"shared\")) }\n}\n")
    val host = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "invalid", "Main.kt:1", 1))) }
    assertFailsWith<AssertionError> {
      assertCompiler("bad", "2.4.21", host, record = SnapshotRecording.ALL, recordVersion = true) {
        fails(); diagnostics { snapshot(SnapshotExpectation("shared"), location = InlineSnapshotLocation(file, 2)) }
      }
    }
    val result = Files.readString(file)
    assertContains(result, "versions = mapOf(\"2.4.21\" to")
    assertContains(result, "error: invalid")
    assertContains(result, "\"shared\"")
  }

  @Test fun `line expectations refer to the primary source`() {
    val compiler = CompilerHost { CompilerResult(false, listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, "bad", "Other.kt:3", 9))) }
    val sources = listOf(CompilerSource("Main.kt", "first"), CompilerSource("Other.kt", "second"))
    assertFailsWith<AssertionError> {
      assertCompiler(sources, "test", compiler) { fails(); diagnostics { error("bad", line = 3) } }
    }
    assertCompiler(sources, "test", compiler) { fails(); diagnostics { error("bad", file = "Other.kt", line = 3, column = 9) } }
  }

  @Test fun `empty diagnostics snapshot checks clean compilation`() {
    assertCompiler("val count = 1", KotlinCompilerVersion.VERSION, EmbeddedCompilerHost(), diagnostics = SnapshotExpectation(""))
  }
  @Test fun `real compiler range accounts for supplementary unicode characters`() {
    val source = "val marker = \"😀\"; val value = unknownIdentifier"
    val result = EmbeddedCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Unicode.kt", source)), KotlinCompilerVersion.VERSION, emptySet()))
    val diagnostic = result.diagnostics.first { it.severity == CompilerDiagnosticSeverity.ERROR }
    assertEquals(source.indexOf("unknownIdentifier") + 1, diagnostic.column)
    assertEquals(source.length + 1, diagnostic.endColumn)
    assertContains(renderCompilerDiagnostics(listOf(CompilerSource("Unicode.kt", source)), result.diagnostics), "^~~~~~~~~~~~~~~~~\nerror:")
  }

}
