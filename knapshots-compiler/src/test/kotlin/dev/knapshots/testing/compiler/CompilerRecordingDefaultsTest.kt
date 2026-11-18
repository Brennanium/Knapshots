package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordingTestCompilerProvider : CompilerHostProvider {
  override fun createCompiler() = CompilerHost { request ->
    CompilerResult(true, captures = request.captures.associateWith { "generated ${it.name}" })
  }
}

class CompilerRecordingDefaultsTest {
  @Test fun `discovers and records the calling test source`(@TempDir directory: Path) {
    val relative = Path.of("dev/knapshots/testing/compiler/CompilerRecordingDefaultsTest.kt")
    val copy = directory.resolve("src/customTest/kotlin").resolve(relative)
    Files.createDirectories(copy.parent)
    Files.copy(Path.of("src/test/kotlin").resolve(relative), copy)
    val previous = System.getProperty("user.dir")
    val previousRecording = System.getProperty("knapshots.compiler.record")
    try {
      System.setProperty("user.dir", directory.toString())
      System.setProperty("knapshots.compiler.record", "missing")
      val failure = assertFailsWith<AssertionError> {
        assertCompiler(source = "class Example", fir = snapshot())
      }
      assertContains(failure.message.orEmpty(), "review and rerun")
      assertContains(Files.readString(copy), "generated FIR")
      assertContains(Files.readString(copy), "fir = SnapshotExpectation(")
    } finally {
      System.setProperty("user.dir", previous)
      if (previousRecording == null) System.clearProperty("knapshots.compiler.record")
      else System.setProperty("knapshots.compiler.record", previousRecording)
    }
  }

  @Test fun `project defaults record a version override and explicit arguments take precedence`(@TempDir directory: Path) {
    val file = directory.resolve("ExampleTest.kt")
    Files.writeString(file, "fun example() {\n  assertCompiler(source = \"example\", fir = SnapshotExpectation(\"shared\"))\n}\n")
    val oldRecord = System.getProperty("knapshots.compiler.record")
    val oldVersion = System.getProperty("knapshots.compiler.recordVersion")
    try {
      System.setProperty("knapshots.compiler.record", "missing")
      System.setProperty("knapshots.compiler.recordVersion", "true")
      assertFailsWith<AssertionError> {
        assertCompiler("class Example", fir = SnapshotExpectation("shared"), location = InlineSnapshotLocation(file, 2))
      }
      val recorded = Files.readString(file)
      assertContains(recorded, KotlinCompilerVersion.VERSION)
      assertContains(recorded, "generated FIR")
      assertFailsWith<IllegalStateException> {
        assertCompiler("class Example", fir = snapshot(), record = SnapshotRecording.NONE, location = InlineSnapshotLocation(file, 2))
      }
      assertEquals(recorded, Files.readString(file))
    } finally {
      if (oldRecord == null) System.clearProperty("knapshots.compiler.record") else System.setProperty("knapshots.compiler.record", oldRecord)
      if (oldVersion == null) System.clearProperty("knapshots.compiler.recordVersion") else System.setProperty("knapshots.compiler.recordVersion", oldVersion)
    }
  }
}
