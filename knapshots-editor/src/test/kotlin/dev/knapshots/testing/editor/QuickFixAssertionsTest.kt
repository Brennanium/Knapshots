package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.SnapshotRecording
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class QuickFixAssertionsTest {
  private fun host() = EditorSourceHost {
    object : EditorObservations {
      override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "bad", "Test.kt:1:1"))
      override fun reference(expression: String) = ReferenceObservation.Unresolved
      override fun quickFix(request: QuickFixRequest): QuickFixObservation = when (request.description) {
        "First" -> QuickFixObservation.Applied("first result")
        "Second" -> QuickFixObservation.Applied("second result")
        else -> QuickFixObservation.Unavailable("no matching fix")
      }
    }
  }

  @Test fun `checks multiple fixes and reports unavailable matches`() {
    assertEditor("original", host(), record = SnapshotRecording.NONE) {
      diagnostics { error("bad") }
      quickFix("First") { fixedSource("first result") }
      quickFix("Second") { fixedSource("second result") }
    }
    val error = assertFailsWith<AssertionError> {
      assertEditor("original", host(), record = SnapshotRecording.NONE) {
        diagnostics { error("bad") }
        quickFix("missing") { fixedSource("anything") }
      }
    }
    assertContains(error.message.orEmpty(), "no matching fix")
    val mismatch = assertFailsWith<AssertionError> {
      assertEditor("original", host(), record = SnapshotRecording.NONE) {
        diagnostics { error("bad") }
        quickFix("First") { fixedSource("wrong") }
      }
    }
    assertContains(mismatch.message.orEmpty(), "− wrong\n+ first result")
  }

  @Test fun `records multiple fixes and diagnostics together`(@TempDir directory: Path) {
    val file = directory.resolve("Test.kt")
    Files.writeString(file, """fun test() {
  assertEditor("original") {
    quickFix("First") {
      fixedSource()
    }
    quickFix("Second") {
      fixedSource()
    }
  }
}
""")
    val error = assertFailsWith<AssertionError> {
      assertEditor("original", host(), record = SnapshotRecording.MISSING, location = EditorSnapshotLocation(file, 2)) {
        quickFix("First") { fixedSource(location = EditorSnapshotLocation(file, 4)) }
        quickFix("Second") { fixedSource(location = EditorSnapshotLocation(file, 7)) }
      }
    }
    assertContains(error.message.orEmpty(), "Recorded editor diagnostics")
    val result = Files.readString(file)
    assertContains(result, "first result")
    assertContains(result, "second result")
    assertContains(result, "error: bad")
  }

  @Test fun `record all replaces an existing fixed source`(@TempDir directory: Path) {
    val file = directory.resolve("Test.kt")
    Files.writeString(file, "fun test() { fixedSource(\"old\") }\n")
    assertFailsWith<AssertionError> {
      assertEditor("original", host(), record = SnapshotRecording.ALL) {
        diagnostics { error("bad") }
        quickFix("First") { fixedSource("old", location = EditorSnapshotLocation(file, 1)) }
      }
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "first result")
    assertFalse(recorded.contains("old"))
  }

  @Test fun `selected IDE branch overrides matching quick fix`() {
    val versionedHost = object : EditorSourceHost {
      override fun ideBuild() = "AI-262.100.1"
      override fun open(request: EditorRequest) = host().open(request)
    }
    assertEditor("original", versionedHost, record = SnapshotRecording.NONE) {
      diagnostics { error("bad") }
      quickFix("First") { fixedSource("wrong default") }
      versioned("AI-262.*") {
        quickFix("First") { fixedSource("first result") }
      }
    }
  }

  @Test fun `does not partially record when a fix is unavailable`(@TempDir directory: Path) {
    val file = directory.resolve("Test.kt")
    val original = "fun test() { fixedSource() }\n"
    Files.writeString(file, original)
    assertFailsWith<AssertionError> {
      assertEditor("original", host(), record = SnapshotRecording.MISSING) {
        diagnostics { error("bad") }
        quickFix("First") { fixedSource(location = EditorSnapshotLocation(file, 1)) }
        quickFix("missing") { fixedSource(location = EditorSnapshotLocation(file, 1)) }
      }
    }
    assertEquals(original, Files.readString(file))
  }
}
