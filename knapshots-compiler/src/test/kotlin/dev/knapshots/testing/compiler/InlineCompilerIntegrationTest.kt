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
  @org.junit.jupiter.api.Test fun `records and compares bytecode snapshots`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
    val file = directory.resolve("BytecodeTest.kt")
    java.nio.file.Files.writeString(file, "fun test() {\n  assertCompiler(source = \"class A\", bytecode = snapshot())\n}\n")
    val host = CompilerHost { request ->
      kotlin.test.assertEquals(setOf(CompilerSnapshotKind.BYTECODE), request.captures)
      CompilerResult(true, captures = mapOf(CompilerSnapshotKind.BYTECODE to "CLASS fixture/A"))
    }
    kotlin.test.assertFailsWith<AssertionError> {
      assertCompiler("class A", "2.4.21", host, record = SnapshotRecording.ALL,
        location = InlineSnapshotLocation(file, 2), bytecode = snapshot())
    }
    kotlin.test.assertContains(java.nio.file.Files.readString(file), "CLASS fixture/A")
    assertCompiler("class A", "2.4.21", host, bytecode = SnapshotExpectation("CLASS fixture/A"))
  }
  @org.junit.jupiter.api.Test fun `discovers recording calls in an explicit source root`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
    val source = directory.resolve("dev/knapshots/testing/compiler/InlineCompilerIntegrationTest.kt")
    java.nio.file.Files.createDirectories(source.parent)
    java.nio.file.Files.writeString(source, "// source fixture")
    val location = discoverInlineSnapshotLocation(listOf(directory))
    kotlin.test.assertEquals(source, location.file)
    kotlin.test.assertTrue(location.line > 0)
  }
}
