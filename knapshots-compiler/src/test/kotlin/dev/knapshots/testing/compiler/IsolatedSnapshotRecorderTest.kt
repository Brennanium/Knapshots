package dev.knapshots.testing.compiler

import dev.knapshots.testing.snapshot.IsolatedSnapshotRecorder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IsolatedSnapshotRecorderTest {
  @Test fun `worker inserts editor snapshot alongside a named location`(@TempDir directory: Path) {
    val original = "fun test() {\n  sourceDiagnosticsSnapshot(location = here)\n}\n"
    val file = directory.resolve("Editor.kt")
    Files.writeString(file, original)
    val classpath = checkNotNull(System.getProperty("knapshots.recorder.testClasspath")).split(java.io.File.pathSeparator).map(Path::of)
    IsolatedSnapshotRecorder(classpath).use { worker ->
      worker.record(file, 2, "sourceDiagnosticsSnapshot", mapOf("expected" to "diagnostic"), original)
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "expected = ")
    assertContains(recorded, "diagnostic")
    assertContains(recorded, "location = here")
  }

  @Test fun `worker records multiple assertions and rejects external edits`(@TempDir directory: Path) {
    val original = "fun first() {\n  assertCompiler(source = \"first\", fir = snapshot())\n}\nfun second() {\n  assertCompiler(source = \"second\", fir = snapshot())\n}\n"
    val file = directory.resolve("Fixture.kt")
    Files.writeString(file, original)
    val classpath = checkNotNull(System.getProperty("knapshots.recorder.testClasspath")).split(java.io.File.pathSeparator).map(Path::of)
    IsolatedSnapshotRecorder(classpath).use { worker ->
      worker.record(file, 2, "assertCompiler", mapOf("fir" to "first result"), original)
      worker.record(file, 5, "assertCompiler", mapOf("fir" to "second result"), original)
      val recorded = Files.readString(file)
      assertContains(recorded, "first result")
      assertContains(recorded, "second result")
      Files.writeString(file, recorded + "// external change\n")
      assertFailsWith<IllegalStateException> { worker.record(file, 2, "assertCompiler", mapOf("fir" to "unsafe"), original) }
      assertEquals(recorded + "// external change\n", Files.readString(file))
    }
  }
}
