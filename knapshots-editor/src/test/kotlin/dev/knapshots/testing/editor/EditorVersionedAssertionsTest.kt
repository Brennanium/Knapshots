package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.SnapshotRecording
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditorVersionedAssertionsTest {
  private fun host(build: String?, error: String = "problem", opened: (EditorRequest) -> Unit = {}, release: EditorRelease? = null): EditorSourceHost =
    object : EditorSourceHost {
      override fun ideBuild() = build
      override fun ideRelease() = release
      override fun open(request: EditorRequest): EditorObservations {
        opened(request)
        return object : EditorObservations {
          override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, error, "Test.kt:1:1"))
          override fun reference(expression: String) = if (expression == "resolved") ReferenceObservation.Resolved() else ReferenceObservation.Unresolved
        }
      }
    }

  @Test fun `selectors match product build families and exact overrides`() {
    assertEquals("AI-262.*", selectEditorBuild(setOf("AI-262.*", "IU-262.*"), "AI-262.1.2.3"))
    assertEquals("AI-262.1.2.3", selectEditorBuild(setOf("AI-262.*", "AI-262.1.2.3"), "AI-262.1.2.3"))
    assertEquals(null, selectEditorBuild(setOf("IU-262.*"), "AI-262.1.2.3"))
    assertFailsWith<IllegalArgumentException> { selectEditorBuild(setOf("AI-262.*", "AI-262.1.*"), "AI-262.1.2") }
    assertFailsWith<IllegalArgumentException> { selectEditorBuild(setOf("2026.2.*"), "AI-262.1.2") }
  }

  @Test fun `diagnostic snapshots select actual IDE build independently of compiler version`() {
    assertEditor("bad", host("AI-262.10.3"), record = SnapshotRecording.NONE) {
      diagnostics { default("wrong"); versioned("AI-262.*", "bad\n^\nerror: problem"); versioned("AI-263.*") }
    }
    assertEditor("bad", host("AI-261.7.0"), record = SnapshotRecording.NONE) {
      diagnostics { default("bad\n^\nerror: problem"); versioned("AI-262.*", "wrong") }
    }
  }

  @Test fun `top-level branches replace individual default references and diagnostics`() {
    var requested = emptySet<String>()
    assertEditor("bad", host("AI-262.10.3", opened = { requested = it.referenceExpressions }), record = SnapshotRecording.NONE) {
      reference("resolved").resolves()
      default { diagnostics { error("old error") }; reference("other").resolves() }
      versioned("AI-262.*") { diagnostics { error("problem") }; reference("other").isUnresolved() }
    }
    assertEquals(setOf("resolved", "other"), requested)
  }

  @Test fun `unknown IDE build fails before opening project`() {
    var opened = false
    val error = assertFailsWith<IllegalArgumentException> {
      assertEditor("bad", host(null, opened = { opened = true }), record = SnapshotRecording.NONE) {
        diagnostics { default("x"); versioned("AI-262.*", "y") }
      }
    }
    assertContains(error.message.orEmpty(), "did not provide an IDE build")
    assertEquals(false, opened)
  }

  @Test fun `overridden default location is not read`(@TempDir directory: Path) {
    val fake = host("AI-262.10.3")
    assertEditor("bad", fake, record = SnapshotRecording.MISSING) {
      diagnostics()
      versioned("AI-262.*") { diagnostics { error("problem") } }
    }
  }

  @Test fun `selected IDE placeholder records without editing inactive placeholders`(@TempDir directory: Path) {
    val file = directory.resolve("VersionedEditor.kt")
    Files.writeString(file, "fun test() {\n  diagnostics {\n    default()\n    versioned(\"AI-262.*\")\n    versioned(\"AI-263.*\")\n  }\n}\n")
    val error = assertFailsWith<AssertionError> {
      assertEditor("bad", host("AI-262.10.3"), record = SnapshotRecording.MISSING) {
        diagnostics { default(); versioned("AI-262.*", location = EditorSnapshotLocation(file, 4)); versioned("AI-263.*") }
      }
    }
    assertContains(error.message.orEmpty(), "Recorded")
    val output = Files.readString(file)
    assertContains(output, "default()")
    assertContains(output, "versioned(\"AI-263.*\")")
    assertContains(output, "versioned(\"AI-262.*\", \"\"\"")
    assertContains(output, "error: problem")
  }

  @Test fun `missing selected placeholder fails without recording`() {
    val error = assertFailsWith<AssertionError> {
      assertEditor("bad", host("AI-262.10.3"), record = SnapshotRecording.NONE) {
        diagnostics { default("wrong"); versioned("AI-262.*") }
      }
    }
    assertContains(error.message.orEmpty(), "Missing source diagnostic snapshot")
  }

  @Test fun `defaults and duplicate selectors conflict before opening project`() {
    assertFailsWith<IllegalStateException> {
      assertEditor("bad", host("AI-262.1"), record = SnapshotRecording.NONE) {
        diagnostics { error("a") }
        default { diagnostics { error("b") } }
      }
    }
    assertFailsWith<IllegalStateException> {
      assertEditor("bad", host("AI-262.1"), record = SnapshotRecording.NONE) {
        versioned("AI-262.*") { reference("x").resolves() }
        versioned("AI-262.*") { reference("y").resolves() }
      }
    }
  }
  @Test fun `versioned recording refuses to overwrite edits made while the IDE runs`(@TempDir directory: Path) {
    val file = directory.resolve("Edited.kt")
    val source = "fun test() {\n  versioned(\"AI-262.*\")\n}\n"
    Files.writeString(file, source)
    val editingHost = host("AI-262.10.3", opened = { Files.writeString(file, source + "// developer edit\n") })
    val failure = assertFailsWith<IllegalStateException> {
      assertEditor("bad", editingHost, record = SnapshotRecording.MISSING) {
        diagnostics { default("old"); versioned("AI-262.*", location = EditorSnapshotLocation(file, 2)) }
      }
    }
    assertContains(failure.message.orEmpty(), "Source changed")
    assertEquals(source + "// developer edit\n", Files.readString(file))
  }

  @Test fun `successive IDE builds record their own snapshots without overwriting each other`(@TempDir directory: Path) {
    val file = directory.resolve("Both.kt")
    Files.writeString(file, "fun test() {\n  diagnostics {\n    default()\n    versioned(\"AI-262.*\")\n  }\n}\n")
    for (build in listOf("AI-261.1", "AI-262.1")) {
      assertFailsWith<AssertionError> {
        assertEditor("bad", host(build, error = build), record = SnapshotRecording.MISSING) {
          diagnostics { default(location = EditorSnapshotLocation(file, 3)); versioned("AI-262.*", location = EditorSnapshotLocation(file, 4)) }
        }
      }
    }
    val output = Files.readString(file)
    assertContains(output, "error: AI-261.1")
    assertContains(output, "error: AI-262.1")
    for (build in listOf("AI-261.1", "AI-262.1")) {
      assertEditor("bad", host(build, error = build), record = SnapshotRecording.NONE) {
        diagnostics { default("bad\n^\nerror: AI-261.1"); versioned("AI-262.*", "bad\n^\nerror: AI-262.1") }
      }
    }
  }

  @Test fun `direct observations can carry their IDE build`() {
    val observations = object : EditorObservations {
      override fun ideBuild() = "IU-263.12"
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Resolved()
    }
    assertEditor(observations, record = SnapshotRecording.NONE) {
      default { reference("member").isUnresolved() }
      versioned("IU-263.*") { reference("member").resolves() }
    }
  }

  @Test fun `versioned placeholder discovers its test source without a location`(@TempDir directory: Path) {
    val relative = Path.of("dev/knapshots/testing/editor/EditorVersionedAssertionsTest.kt")
    val copy = directory.resolve("src/customTest/kotlin").resolve(relative)
    Files.createDirectories(copy.parent)
    Files.copy(Path.of("src/test/kotlin").resolve(relative), copy)
    val previous = System.getProperty("user.dir")
    try {
      System.setProperty("user.dir", directory.toString())
      assertFailsWith<AssertionError> { assertEditor("bad", host("AI-262.1"), record = SnapshotRecording.MISSING) { diagnostics { default("unused"); versioned("AI-262.*") } } }
      assertContains(Files.readString(copy), "versioned(\"AI-262.*\", \"\"\"")
    } finally {
      System.setProperty("user.dir", previous)
    }
  }

  @Test fun `release numbers and names select verified Android Studio metadata`() {
    val build = "AI-262.10968.63.2622.16730706"
    val release = EditorRelease(build, "2026.2.2.8", "Android Studio Rabbit 2 | 2026.2.2")
    for (selector in listOf("2026.2.2.8", "2026.2.2.*", "Rabbit", "Rabbit 2")) {
      assertEquals(selector, selectEditorBuild(setOf(selector), build) { release })
      assertEditor("bad", host(build, release = release), record = SnapshotRecording.NONE) {
        diagnostics { default("wrong"); versioned(selector, "bad\n^\nerror: problem") }
      }
    }
    assertEquals(null, selectEditorBuild(setOf("Rabbit 3"), build) { release })
    assertFailsWith<IllegalArgumentException> { selectEditorBuild(setOf("Rabbit", "Rabbit 2"), build) { release } }
    assertFailsWith<IllegalArgumentException> { selectEditorBuild(setOf("2026.2.2.8"), build) { release.copy(build = "AI-263.1") } }
    assertFailsWith<IllegalArgumentException> { selectEditorBuild(setOf("Rabbit"), build) { null } }
    assertEquals(null, selectEditorBuild(setOf("Rabbit"), "IU-262.1") { error("not Android Studio") })
  }

  @Test fun `build-only selectors do not resolve release metadata`() {
    assertEquals("AI-262.*", selectEditorBuild(setOf("AI-262.*"), "AI-262.1") { error("not needed") })
  }

  @Test fun `official release feed lookup validates exact build and ambiguity`() {
    val item = """<item><name>Android Studio Rabbit 2 | 2026.2.2</name><version>2026.2.2.8</version><build>AI-262.123</build></item>"""
    val xml = "<releases>$item</releases>"
    assertEquals(EditorRelease("AI-262.123", "2026.2.2.8", "Android Studio Rabbit 2 | 2026.2.2"),
      AndroidStudioReleaseMetadata.find("AI-262.123", xml.byteInputStream()))
    assertEquals(null, AndroidStudioReleaseMetadata.find("AI-262.456", xml.byteInputStream()))
    val conflict = "<releases>$item${item.replace("2026.2.2.8", "2026.2.2.9")}</releases>"
    assertFailsWith<IllegalArgumentException> { AndroidStudioReleaseMetadata.find("AI-262.123", conflict.byteInputStream()) }
  }

  @Test fun `records the matching release family or codename`(@TempDir directory: Path) {
    val build = "AI-262.10968.63.2622.16730706"
    val release = EditorRelease(build, "2026.2.2.8", "Android Studio Rabbit 2 | 2026.2.2")
    listOf("2026.2.2.8", "2026.2.2.*", "Rabbit", "Rabbit 2").forEachIndexed { index, selector ->
      val file = directory.resolve("Release$index.kt")
      Files.writeString(file, "fun test() {\n  diagnostics {\n    default()\n    versioned(\"$selector\")\n  }\n}\n")
      assertFailsWith<AssertionError> {
        assertEditor("bad", host(build, release = release), record = SnapshotRecording.MISSING) {
          diagnostics { default(); versioned(selector, location = EditorSnapshotLocation(file, 4)) }
        }
      }
      val recorded = Files.readString(file)
      assertContains(recorded, "default()")
      assertContains(recorded, "versioned(\"$selector\", \"\"\"")
      assertContains(recorded, "error: problem")
    }
  }

}
