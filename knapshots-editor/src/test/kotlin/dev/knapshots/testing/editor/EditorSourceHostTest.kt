package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.SnapshotRecording
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorSourceHostTest {
  @Test fun `scoped hosts restore outer configuration after nested failure`() {
    val calls = mutableListOf<String>()
    fun host(name: String) = EditorSourceHost {
      calls += name
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertFailsWith<IllegalStateException> { assertEditor("class Example", checkDiagnostics = false) {} }
    withEditorHost(host("outer")) {
      assertEditor("class Example", checkDiagnostics = false) {}
      assertFailsWith<IllegalArgumentException> {
        withEditorHost(host("inner")) {
          assertEditor("class Example", checkDiagnostics = false) {}
          throw IllegalArgumentException("test")
        }
      }
      assertEditor("class Example", checkDiagnostics = false) {}
    }
    assertEquals(listOf("outer", "inner", "outer"), calls)
    assertFailsWith<IllegalStateException> { assertEditor("class Example", checkDiagnostics = false) {} }
  }

  @Test fun `inline source is opened once and assertions use its observations`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      check(opened == null)
      opened = request
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) =
          if (expression == "person.name") ReferenceObservation.Resolved()
          else ReferenceObservation.Unavailable("not in file")
      }
    }

    assertEditor(
      source = """
        val name = person.name
      """,
      host = host,
      fileName = "Example.kt",
    ) { reference("person.name").resolves() }

    assertEquals(listOf(EditorSource("Example.kt", "val name = person.name")), opened?.sources)
    assertEquals(setOf("person.name"), opened?.referenceExpressions)
    assertEquals(true, opened?.captureDiagnostics)
  }

  @Test fun `source diagnostic snapshot renders line and column`() {
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference", "Test.kt:1:9"))
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertEditor("val x = missing", host) {
      sourceDiagnosticsSnapshot("val x = missing\n        ^\nerror: Unresolved reference")
      reference("missing").isUnresolved()
    }
    assertFailsWith<AssertionError> { assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot("") } }
    assertFailsWith<IllegalStateException> {
      assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot(""); diagnostics { error("Unresolved") } }
    }
  }

  @Test fun `records source diagnostics in an isolated worker`(@TempDir directory: Path) {
    val file = directory.resolve("SnapshotTest.kt")
    val original = "fun test() {\n  sourceDiagnosticsSnapshot()\n}\n"
    Files.writeString(file, original)
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference", "Test.kt:1:9"))
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.setProperty("knapshots.editor.record", "missing")
      val error = assertFailsWith<AssertionError> {
        assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot(location = EditorSnapshotLocation(file, 2)) }
      }
      assertContains(error.message.orEmpty(), "Recorded editor diagnostics")
      val recorded = Files.readString(file)
      assertContains(recorded, "val x = missing")
      assertContains(recorded, "error: Unresolved reference")
      System.setProperty("knapshots.editor.record", "none")
      assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot("val x = missing\n        ^\nerror: Unresolved reference") }
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `diagnostic block records a source snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("SnapshotTest.kt")
    Files.writeString(file, "fun test() {\n  diagnostics { snapshot() }\n}\n")
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference", "Test.kt:1:9"))
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.setProperty("knapshots.editor.record", "missing")
      val error = assertFailsWith<AssertionError> {
        assertEditor("val x = missing", host) { diagnostics { snapshot(location = EditorSnapshotLocation(file, 2)) } }
      }
      assertContains(error.message.orEmpty(), "Recorded editor diagnostics")
      assertContains(Files.readString(file), "error: Unresolved reference")
      System.setProperty("knapshots.editor.record", "none")
      assertEditor("val x = missing", host) { diagnostics { snapshot("val x = missing\n        ^\nerror: Unresolved reference") } }
      assertFailsWith<IllegalStateException> {
        assertEditor("val x = missing", host) { diagnostics { snapshot(""); error("Unresolved") } }
      }
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `diagnostics shorthand records an editor snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("Short.kt")
    Files.writeString(file, "fun test() {\n  diagnostics()\n}\n")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference", "Test.kt:1:9"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.setProperty("knapshots.editor.record", "missing")
      assertFailsWith<AssertionError> { assertEditor("val x = missing", host) { diagnostics(EditorSnapshotLocation(file, 2)) } }
      assertContains(Files.readString(file), "diagnostics { snapshot(")
      assertContains(Files.readString(file), "error: Unresolved reference")
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `failed recording replaces an existing diagnostic snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("Existing.kt")
    Files.writeString(file, "fun test() {\n  diagnostics { snapshot(\"old\") }\n}\n")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved", "Test.kt:1:9"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.setProperty("knapshots.editor.record", "failed")
      assertFailsWith<AssertionError> {
        assertEditor("val x = missing", host) { diagnostics { snapshot("old", EditorSnapshotLocation(file, 2)) } }
      }
      assertContains(Files.readString(file), "error: Unresolved")
      check(!Files.readString(file).contains("snapshot(\"old\")"))
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `per assertion recording overrides the project default`(@TempDir directory: Path) {
    val file = directory.resolve("PerAssertion.kt")
    Files.writeString(file, "fun test() {\n  diagnostics()\n}\n")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved", "Test.kt:1:9"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.setProperty("knapshots.editor.record", "none")
      assertFailsWith<AssertionError> {
        assertEditor("val x = missing", host, record = SnapshotRecording.MISSING) { diagnostics(EditorSnapshotLocation(file, 2)) }
      }
      assertContains(Files.readString(file), "error: Unresolved")
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `focused diagnostics use virtual source names lines and columns`() {
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "bad", "Other.kt:3:9"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    val extra = listOf(EditorSource("Other.kt", "one\ntwo\ninvalid"))
    assertFailsWith<AssertionError> {
      assertEditor("class Main", host, additionalSources = extra) { diagnostics { error("bad", line = 3) } }
    }
    assertEditor("class Main", host, additionalSources = extra) {
      diagnostics { error("bad", file = "Other.kt", line = 3, column = 9) }
    }
    assertFailsWith<AssertionError> {
      assertEditor("class Main", host, additionalSources = extra) { diagnostics { error("bad", file = "Other.kt", line = 3, column = 2) } }
    }
  }

  @Test fun `input only recording inserts editor diagnostics`(@TempDir directory: Path) {
    val file = directory.resolve("InputOnly.kt")
    Files.writeString(file, "fun test() {\n  assertEditor(\"val x = missing\")\n}\n")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved", "Test.kt:1:9"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    assertFailsWith<AssertionError> {
      assertEditor("val x = missing", host, record = SnapshotRecording.MISSING, location = EditorSnapshotLocation(file, 2))
    }
    assertContains(Files.readString(file), "assertEditor(\"val x = missing\") { diagnostics { snapshot(")
    assertContains(Files.readString(file), "error: Unresolved")
    assertEditor("val x = missing", host, record = SnapshotRecording.NONE) {
      diagnostics { snapshot("val x = missing\n        ^\nerror: Unresolved") }
    }
  }

  @Test fun `missing mode compares existing diagnostics without locating test source`(@TempDir directory: Path) {
    val previous = System.getProperty("user.dir")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    try {
      System.setProperty("user.dir", directory.toString())
      assertEditor("class Input", host, record = SnapshotRecording.MISSING) { diagnostics { snapshot("") } }
    } finally { System.setProperty("user.dir", previous) }
  }

  @Test fun `input only defaults to recording missing editor diagnostics`(@TempDir directory: Path) {
    val file = directory.resolve("Default.kt")
    Files.writeString(file, "fun test() {\n  assertEditor(\"class Example\")\n}\n")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    val previous = System.getProperty("knapshots.editor.record")
    try {
      System.clearProperty("knapshots.editor.record")
      assertFailsWith<AssertionError> { assertEditor("class Example", host, location = EditorSnapshotLocation(file, 2)) }
      assertContains(Files.readString(file), "diagnostics { snapshot(")
    } finally {
      if (previous == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previous)
    }
  }

  @Test fun `diagnostic recording modes preserve or replace editor snapshots`(@TempDir directory: Path) {
    val actual = "invalid\n^\nerror: bad"
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "bad", "Test.kt:1:1"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
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
      val run = { assertEditor("invalid", host, record = mode) {
        diagnostics { snapshot(expected, EditorSnapshotLocation(file, 2)) }
      } }
      val shouldPass = !changes && expected == actual
      if (shouldPass) run() else assertFailsWith<AssertionError> { run() }
      if (changes) assertContains(Files.readString(file), "error: bad") else assertEquals(original, Files.readString(file))
    }
  }

  @Test fun `input only recording never overwrites a source edit`(@TempDir directory: Path) {
    val file = directory.resolve("Edited.kt")
    Files.writeString(file, "fun test() {\n  assertEditor(\"example\")\n}\n")
    val edit = "// changed by developer\n"
    val host = EditorSourceHost {
      Files.writeString(file, edit)
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    val target = EditorSnapshotLocation(file, 2)
    assertFailsWith<AssertionError> { assertEditor("example", host, record = SnapshotRecording.MISSING, location = target) }
    assertEquals(edit, Files.readString(file))
  }

  @Test fun `input only discovers the editor test source`(@TempDir directory: Path) {
    val relative = Path.of("dev/knapshots/testing/editor/EditorSourceHostTest.kt")
    val copy = directory.resolve("src/customTest/kotlin").resolve(relative)
    Files.createDirectories(copy.parent)
    Files.copy(Path.of("src/test/kotlin").resolve(relative), copy)
    val previousDirectory = System.getProperty("user.dir")
    val previousRecording = System.getProperty("knapshots.editor.record")
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    try {
      System.setProperty("user.dir", directory.toString())
      System.clearProperty("knapshots.editor.record")
      assertFailsWith<AssertionError> { assertEditor("class Example", host) }
      assertContains(Files.readString(copy), "assertEditor(\"class Example\", host) { diagnostics { snapshot(")
    } finally {
      System.setProperty("user.dir", previousDirectory)
      if (previousRecording == null) System.clearProperty("knapshots.editor.record") else System.setProperty("knapshots.editor.record", previousRecording)
    }
  }

  @Test fun `comparison only input requires a saved diagnostic snapshot`(@TempDir directory: Path) {
    val file = directory.resolve("NoRecord.kt")
    val original = "fun test() {\n  assertEditor(\"class Input\")\n}\n"
    Files.writeString(file, original)
    val host = EditorSourceHost { object : EditorObservations {
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Unresolved
    } }
    assertFailsWith<AssertionError> { assertEditor("class Input", host, record = SnapshotRecording.NONE, location = EditorSnapshotLocation(file, 2)) }
    assertEquals(original, Files.readString(file))
  }

  @Test fun `hosting does not suppress unexpected editor errors`() {
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Type mismatch"))
        override fun reference(expression: String) = ReferenceObservation.Unavailable("not inspected")
      }
    }
    assertFailsWith<AssertionError> { assertEditor("val value: Int = \"wrong\"", host) {} }
  }

  @Test fun `source assertions complete managed sessions with their result`() {
    val outcomes = mutableListOf<Boolean>()
    val host = EditorSourceHost {
      object : EditorObservationSession {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
        override fun complete(successful: Boolean) {
          outcomes += successful
        }
      }
    }

    assertEditor("class Example", host) { reference("missing").isUnresolved() }
    assertFailsWith<AssertionError> {
      assertEditor("class Example", host) { reference("missing").resolves() }
    }

    assertEquals(listOf(true, false), outcomes)
  }

  @Test fun `additional sources and disabled diagnostics are sent to the host`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      opened = request
      object : EditorObservations {
        override fun diagnostics(): List<EditorDiagnostic> = error("diagnostics should not be read")
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }

    assertEditor(
      source = "class First",
      host = host,
      fileName = "First.kt",
      additionalSources = listOf(EditorSource("second/Second.kt", "  class Second")),
      checkDiagnostics = false,
    ) {
      reference("missing.member").isUnresolved()
    }

    assertEquals(
      listOf(EditorSource("First.kt", "class First"), EditorSource("second/Second.kt", "class Second")),
      opened?.sources,
    )
    assertEquals(setOf("missing.member"), opened?.referenceExpressions)
    assertEquals(false, opened?.captureDiagnostics)
  }

  @Test fun `explicit readiness prerequisites are sent separately from assertions`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      opened = request
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertEditor("val item = Example.generated", host, awaitReferences = setOf("Example.ready")) {
      reference("Example.generated").isUnresolved()
    }
    assertEquals(setOf("Example.ready"), opened?.readinessReferences)
    assertEquals(setOf("Example.generated"), opened?.referenceExpressions)
  }
}
