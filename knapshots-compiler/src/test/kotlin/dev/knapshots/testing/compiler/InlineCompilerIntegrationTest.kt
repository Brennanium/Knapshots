package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class InlineCompilerIntegrationTest {
  @Test fun `records a missing snapshot then asks for review`(@TempDir directory: Path) {
    val file = directory.resolve("Sample.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(source = \"class A\", fir = snapshot())\n}\n")
    val failure = assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.4.21", CompilerHost {
        CompilerResult(true, captures = mapOf(CompilerSnapshotKind.FIR to "class A"))
      }, fir = snapshot(), record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    assertContains(failure.message.orEmpty(), "review and rerun")
    assertContains(Files.readString(file), "class A")
    assertContains(Files.readString(file), "SnapshotExpectation(")
  }

  @Test fun `never records failed compilations`(@TempDir directory: Path) {
    val file = directory.resolve("Sample.kt")
    val original = "fun test() {\n  assertCompiler(source = \"class A\", fir = snapshot())\n}\n"
    Files.writeString(file, original)
    assertFailsWith<IllegalStateException> {
      assertCompiler("class A", "2.4.21", CompilerHost { CompilerResult(false) },
        fir = snapshot(), record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    kotlin.test.assertEquals(original, Files.readString(file))
  }
}
