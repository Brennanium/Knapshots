package dev.knapshots.testing.ksp

import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.SnapshotRecording
import dev.knapshots.testing.compiler.snapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KspAssertionsTest {
  private val host = KspHost { request ->
    check(request.sources.map { it.fileName } == listOf("Main.kt", "Other.kt"))
    KspResult(true, mapOf("kotlin/z/Z.kt" to "class Z\n", "kotlin/a/A.kt" to "class A\n"))
  }

  @Test fun `compares sorted generated files and additional sources`() {
    assertKsp("class Input", "2.4.21", host, generated = SnapshotExpectation("""
      // FILE: kotlin/a/A.kt
      class A

      // FILE: kotlin/z/Z.kt
      class Z
    """), additionalSources = listOf(KspSource("Other.kt", "class Other")))
  }

  @Test fun `records generated source and diagnostics together atomically`(@TempDir directory: Path) {
    val file = directory.resolve("Combined.kt")
    val original = "fun test() {\n  assertKsp(\"class Input\") {\n    generated()\n    diagnostics()\n  }\n}\n"
    Files.writeString(file, original)
    val fake = KspHost { KspResult(true, mapOf("kotlin/Out.kt" to "class Out"), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.WARNING, "notice", "src/main/kotlin/Main.kt", 1, 1))) }
    val failure = assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", fake, record = SnapshotRecording.MISSING) {
        generated(location = InlineSnapshotLocation(file, 3))
        diagnostics(InlineSnapshotLocation(file, 4))
      }
    }
    assertContains(failure.message.orEmpty(), "Recorded inline snapshot")
    val recorded = Files.readString(file)
    assertContains(recorded, "generated(")
    assertContains(recorded, "// FILE: kotlin/Out.kt")
    assertContains(recorded, "diagnostics { snapshot(")
    assertContains(recorded, "warning: notice")
  }

  @Test fun `a failed batch leaves the entire test unchanged`(@TempDir directory: Path) {
    val file = directory.resolve("Combined.kt")
    val original = "fun test() {\n  assertKsp(\"class Input\") {\n    generated()\n    diagnostics(unsupported = true)\n  }\n}\n"
    Files.writeString(file, original)
    val fake = KspHost { KspResult(true, mapOf("kotlin/Out.kt" to "class Out")) }
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", fake, record = SnapshotRecording.MISSING) {
        generated(location = InlineSnapshotLocation(file, 3))
        diagnostics(InlineSnapshotLocation(file, 4))
      }
    }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `line-only diagnostics refer to the primary file`() {
    val other = KspHost { KspResult(false, emptyMap(), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.ERROR, "bad", "src/main/kotlin/Other.kt", 3))) }
    assertFailsWith<AssertionError> { assertKsp("class Input", "2.4.21", other) { fails(); diagnostics { error("bad", line = 3) } } }
    assertKsp("class Input", "2.4.21", other) { fails(); diagnostics { error("bad", file = "Other.kt", line = 3) } }
  }

  @Test fun `diagnostics block selects and records versioned expectations`(@TempDir directory: Path) {
    val fake = KspHost { KspResult(true, emptyMap(), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.WARNING, "notice", "src/main/kotlin/Main.kt", 1, 1))) }
    val output = "class Input\n^\nwarning: notice"
    assertKsp("class Input", "2.4.21", fake) {
      succeeds(); diagnostics { snapshot(SnapshotExpectation("wrong", versions = mapOf("2.4.21" to output))) }
    }
    val file = directory.resolve("Version.kt")
    Files.writeString(file, "fun test() {\n  diagnostics { snapshot(SnapshotExpectation(\"shared\")) }\n}\n")
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", fake, record = SnapshotRecording.ALL, recordVersion = true) {
        succeeds(); diagnostics { snapshot(SnapshotExpectation("shared"), location = InlineSnapshotLocation(file, 2)) }
      }
    }
    val result = Files.readString(file)
    assertContains(result, "versions = mapOf(\"2.4.21\" to")
    assertContains(result, "warning: notice")
  }

  @Test fun `input only recording captures generated output and diagnostics`(@TempDir directory: Path) {
    val file = directory.resolve("InputOnly.kt")
    Files.writeString(file, "fun test() {\n  assertKsp(\"invalid\")\n}\n")
    val failed = KspHost { KspResult(false, mapOf("kotlin/Partial.kt" to "class Partial"), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.ERROR, "bad", "src/main/kotlin/Main.kt", 1, 1))) }
    assertFailsWith<AssertionError> {
      assertKsp("invalid", "2.4.21", failed, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "generated(")
    assertContains(recorded, "class Partial")
    assertContains(recorded, "diagnostics { snapshot(")
    assertContains(recorded, "error: bad")
    val stubs = "class Diagnostics { fun snapshot(value: String) {} }\n" +
      "class Expectations { fun generated(value: String) {} ; fun diagnostics(block: Diagnostics.() -> Unit) {} }\n" +
      "fun assertKsp(source: String, block: Expectations.() -> Unit) {}\n"
    val rewritten = dev.knapshots.testing.compiler.EmbeddedCompilerHost().compile(dev.knapshots.testing.compiler.CompilerRequest(
      listOf(dev.knapshots.testing.compiler.CompilerSource("Rewritten.kt", stubs + recorded)), org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION, emptySet(),
    ))
    kotlin.test.assertTrue(rewritten.succeeded, rewritten.diagnostics.toString())
    assertKsp("invalid", "2.4.21", failed, record = SnapshotRecording.NONE) {
      generated("// FILE: kotlin/Partial.kt\nclass Partial")
      diagnostics { snapshot("invalid\n^\nerror: bad") }
    }
    assertKsp("invalid", "2.4.21", failed, record = SnapshotRecording.NONE) {
      generated("// FILE: kotlin/Partial.kt\nclass Partial")
    }
    assertKsp("invalid", "2.4.21", failed, record = SnapshotRecording.MISSING) { fails() }
    assertKsp("invalid", "2.4.21", KspHost { KspResult(false, emptyMap(), "unparsed failure") }, record = SnapshotRecording.MISSING) { fails() }
    assertKsp("invalid", "2.4.21", KspHost { KspResult(false, emptyMap(), "unparsed failure") }, record = SnapshotRecording.NONE) { diagnostics { snapshot("") } }
    assertFailsWith<AssertionError> { assertKsp("invalid", "2.4.21", failed, record = SnapshotRecording.NONE) }
  }

  @Test fun `missing mode compares existing snapshots without locating test source`(@TempDir directory: Path) {
    val previous = System.getProperty("user.dir")
    try {
      System.setProperty("user.dir", directory.toString())
      val host = KspHost { KspResult(true, emptyMap()) }
      assertKsp("class Input", "2.4.21", host, record = SnapshotRecording.MISSING) {
        generated("")
        diagnostics { snapshot("") }
      }
    } finally { System.setProperty("user.dir", previous) }
  }

  @Test fun `input only defaults to recording missing output`(@TempDir directory: Path) {
    val file = directory.resolve("Default.kt")
    Files.writeString(file, "fun test() {\n  assertKsp(\"class Input\")\n}\n")
    val previous = System.getProperty("knapshots.ksp.record")
    try {
      System.clearProperty("knapshots.ksp.record")
      assertFailsWith<AssertionError> {
        assertKsp("class Input", "2.4.21", KspHost { KspResult(true, emptyMap()) }, location = InlineSnapshotLocation(file, 2))
      }
      assertContains(Files.readString(file), "generated(")
      assertContains(Files.readString(file), "diagnostics { snapshot(")
    } finally {
      if (previous == null) System.clearProperty("knapshots.ksp.record") else System.setProperty("knapshots.ksp.record", previous)
    }
  }

  @Test fun `diagnostic recording modes preserve or replace the expected snapshot`(@TempDir directory: Path) {
    val actual = "invalid\n^\nerror: bad"
    val host = KspHost { KspResult(false, emptyMap(), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.ERROR, "bad", "src/main/kotlin/Main.kt", 1, 1))) }
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
      val run = { assertKsp("invalid", "2.4.21", host, record = mode) {
        diagnostics { snapshot(expected, InlineSnapshotLocation(file, 2)) }
      } }
      val shouldPass = !changes && expected == actual
      if (shouldPass) run() else assertFailsWith<AssertionError> { run() }
      if (changes) assertContains(Files.readString(file), "error: bad") else assertEquals(original, Files.readString(file))
    }
  }

  @Test fun `input only discovers the KSP test source`(@TempDir directory: Path) {
    val relative = Path.of("dev/knapshots/testing/ksp/KspAssertionsTest.kt")
    val copy = directory.resolve("src/customTest/kotlin").resolve(relative)
    Files.createDirectories(copy.parent)
    Files.copy(Path.of("src/test/kotlin").resolve(relative), copy)
    val previousDirectory = System.getProperty("user.dir")
    val previousRecording = System.getProperty("knapshots.ksp.record")
    val host = KspHost { KspResult(true, mapOf("kotlin/Generated.kt" to "class Generated")) }
    try {
      System.setProperty("user.dir", directory.toString())
      System.clearProperty("knapshots.ksp.record")
      assertFailsWith<AssertionError> { assertKsp("class Example", "2.4.21", host) }
      val output = Files.readString(copy)
      assertContains(output, "class Generated")
      assertContains(output, "assertKsp(\"class Example\", \"2.4.21\", host) { generated(")
    } finally {
      System.setProperty("user.dir", previousDirectory)
      if (previousRecording == null) System.clearProperty("knapshots.ksp.record") else System.setProperty("knapshots.ksp.record", previousRecording)
    }
  }

  @Test fun `comparison only input requires a saved output snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("NoRecord.kt")
    val original = "fun test() {\n  assertKsp(\"class Input\")\n}\n"
    Files.writeString(file, original)
    val host = KspHost { KspResult(true, emptyMap()) }
    assertFailsWith<AssertionError> { assertKsp("class Input", "2.4.21", host, record = SnapshotRecording.NONE, location = InlineSnapshotLocation(file, 2)) }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `input only recording never overwrites a source edit`(@TempDir directory: Path) {
    val file = directory.resolve("Edited.kt")
    Files.writeString(file, "fun test() {\n  assertKsp(\"input\")\n}\n")
    val edit = "// changed by developer\n"
    val host = KspHost { Files.writeString(file, edit); KspResult(true, emptyMap()) }
    assertFailsWith<AssertionError> { assertKsp("input", "2.4.21", host, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2)) }
    assertEquals(edit, Files.readString(file))
  }

  @Test fun `checks failure and diagnostics`() {
    val failed = KspHost { KspResult(false, emptyMap(), "error: invalid property") }
    assertKsp("class Input", "2.4.21", failed, succeeds = false, diagnostics = listOf("invalid property"))
    assertFailsWith<AssertionError> { assertKsp("class Input", "2.4.21", failed) }
    assertFailsWith<AssertionError> { assertKsp("class Input", "2.4.21", failed, succeeds = false, diagnostics = listOf("different")) }
  }

  @Test fun `records generated output and refuses mismatches when recording is disabled`(@TempDir directory: Path) {
    val file = directory.resolve("ExampleTest.kt")
    Files.writeString(file, "fun test() {\n  assertKsp(source = \"example\", generated = snapshot())\n}\n")
    val failure = assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", host, generated = snapshot(),
        additionalSources = listOf(KspSource("Other.kt", "class Other")),
        record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertContains(failure.message.orEmpty(), "review and rerun")
    val recorded = Files.readString(file)
    assertContains(recorded, "generated = SnapshotExpectation(")
    assertContains(recorded, "// FILE: kotlin/a/A.kt")
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", host, generated = SnapshotExpectation("wrong"),
        additionalSources = listOf(KspSource("Other.kt", "class Other")))
    }
    assertEquals(recorded, Files.readString(file))
  }

  @Test fun `does not record if compilation outcome or diagnostics do not match`(@TempDir directory: Path) {
    val file = directory.resolve("ExampleTest.kt")
    val original = "fun test() {\n  assertKsp(source = \"example\", generated = snapshot())\n}\n"
    Files.writeString(file, original)
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", KspHost { KspResult(false, emptyMap(), "error") },
        generated = snapshot(), succeeds = true, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `uses compiler version snapshot selectors`() {
    assertKsp("class Input", "2.5.0-dev-10106", host, generated = SnapshotExpectation("fallback", mapOf("2.5.*" to """
      // FILE: kotlin/a/A.kt
      class A

      // FILE: kotlin/z/Z.kt
      class Z
    """)), additionalSources = listOf(KspSource("Other.kt", "class Other")))
  }
  @Test fun `records a version override without changing the default`(@TempDir directory: Path) {
    val file = directory.resolve("Versioned.kt")
    Files.writeString(file, "fun test() {\n  assertKsp(source = \"example\", generated = SnapshotExpectation(\"shared\"))\n}\n")
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.5.0", host, generated = SnapshotExpectation("shared"),
        additionalSources = listOf(KspSource("Other.kt", "class Other")), record = SnapshotRecording.MISSING,
        recordVersion = true, location = InlineSnapshotLocation(file, 2))
    }
    val result = Files.readString(file)
    assertContains(result, "shared")
    assertContains(result, "\"2.5.0\" to")
    assertContains(result, "// FILE: kotlin/a/A.kt")
  }

  @Test fun `does not overwrite external source edits during processing`(@TempDir directory: Path) {
    val file = directory.resolve("Edited.kt")
    val original = "fun test() {\n  assertKsp(source = \"example\", generated = snapshot())\n}\n"
    Files.writeString(file, original)
    val editingHost = KspHost {
      Files.writeString(file, original + "// developer edit\n")
      KspResult(true, mapOf("kotlin/Generated.kt" to "class Generated"))
    }
    val failure = assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", editingHost, generated = snapshot(), record = SnapshotRecording.MISSING,
        location = InlineSnapshotLocation(file, 2))
    }
    assertContains(failure.message.orEmpty(), "Could not record inline snapshot")
    assertEquals(original + "// developer edit\n", Files.readString(file))
  }

  @Test fun `generated snapshots preserve carriage returns`() {
    val generated = "first\r\nsecond\rthird"
    val fake = KspHost { KspResult(true, mapOf("kotlin/Out.kt" to generated)) }
    assertKsp("class Input", "2.4.21", fake, generated = SnapshotExpectation("// FILE: kotlin/Out.kt\n$generated"))
    assertFailsWith<AssertionError> {
      assertKsp("class Input", "2.4.21", fake, generated = SnapshotExpectation("// FILE: kotlin/Out.kt\nfirst\nsecond\nthird"))
    }
  }

}
