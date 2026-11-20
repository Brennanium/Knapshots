package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class InlineCompilerSnapshotsTest {
  @Test
  fun `ambiguous version ranges fail without modifying the test file`(@TempDir directory: Path) {
    val file = directory.resolve("Ambiguous.kt")
    val original = """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf(">=2.5.0-dev-1 <2.5.0-dev-60" to "one", ">=2.5.0-dev-30 <2.5.0-dev-80" to "two")),
        )
      }
    """.trimIndent()
    Files.writeString(file, original)
    assertFailsWith<IllegalArgumentException> {
      InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "updated"), "2.5.0-dev-42", false)
    }
    assertEquals(original, Files.readString(file))
  }

  @Test
  fun `recording updates the selected range without changing other overrides`(@TempDir directory: Path) {
    val file = directory.resolve("Range.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.5.*" to "old", "2.4.20" to "older")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "updated"), "2.5.0-dev-42", false)
    val updated = Files.readString(file)
    assertContains(updated, "\"2.5.*\" to \"\"\"")
    assertContains(updated, "updated")
    assertContains(updated, "\"2.4.20\" to \"older\"")
    assertContains(updated, "\"shared\"")
  }

  @Test
  fun `versioned recording adds exact override without replacing a matching range`(@TempDir directory: Path) {
    val file = directory.resolve("Range.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.5.*" to "range")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "specific"), "2.5.0", true)
    val updated = Files.readString(file)
    assertContains(updated, "\"2.5.*\" to \"range\"")
    assertContains(updated, "\"2.5.0\" to")
    assertContains(updated, "specific")
  }
  @Test
  fun `multiple compiled source locations record safely in either order`(@TempDir directory: Path) {
    val original = """
      fun first() {
        assertCompiler(
          source = "first",
        )
      }
      fun second() {
        assertCompiler(
          source = "second",
        )
      }
    """.trimIndent()
    for ((name, lines) in listOf("forward" to listOf(2, 7), "reverse" to listOf(7, 2))) {
      val file = directory.resolve("$name.kt")
      Files.writeString(file, original)
      for (line in lines) {
        InlineCompilerSnapshots.write(
          file, line, mapOf(CompilerSnapshotKind.FIR to "result for $line\nsecond line"), "2.4.21", false,
          expectedSource = original, compiledSourceLine = true,
        )
      }
      val updated = Files.readString(file)
      val first = updated.indexOf("result for 2")
      val second = updated.indexOf("result for 7")
      assertTrue(first >= 0 && second > first, updated)
    }
  }

  @Test
  fun `a batch preserves source history for later recordings`(@TempDir directory: Path) {
    val file = directory.resolve("Batch.kt")
    val original = "fun first() {\n  fir()\n}\nfun second() {\n  ir()\n}\nfun third() {\n  bytecode()\n}\n"
    Files.writeString(file, original)
    recordInlineSnapshotBatch(listOf(
      InlineSnapshotUpdate(InlineSnapshotLocation(file, 2), "fir", mapOf("expected" to "fir output"), original),
      InlineSnapshotUpdate(InlineSnapshotLocation(file, 5), "ir", mapOf("expected" to "ir output"), original),
    ), "2.4.21")
    recordInlineSnapshots(InlineSnapshotLocation(file, 8), "bytecode", mapOf("expected" to "bytecode output"), "2.4.21", expectedSource = original)
    val output = Files.readString(file)
    assertContains(output, "fir output")
    assertContains(output, "ir output")
    assertContains(output, "bytecode output")
  }

  @Test
  fun `concurrent recordings from one compiled file do not lose updates`(@TempDir directory: Path) {
    val original = """
      fun first() {
        assertCompiler(
          source = "first",
        )
      }
      fun second() {
        assertCompiler(
          source = "second",
        )
      }
    """.trimIndent()
    val file = directory.resolve("concurrent.kt")
    Files.writeString(file, original)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val tasks = listOf(2, 7).map { line ->
        executor.submit {
          start.await()
          InlineCompilerSnapshots.write(
            file, line, mapOf(CompilerSnapshotKind.IR to "result for $line"), "2.4.21", false,
            expectedSource = original, compiledSourceLine = true,
          )
        }
      }
      start.countDown()
      tasks.forEach { it.get() }
    } finally {
      executor.shutdownNow()
    }
    val updated = Files.readString(file)
    assertContains(updated, "result for 2")
    assertContains(updated, "result for 7")
  }

  @Test
  fun `external edits after recording are never overwritten`(@TempDir directory: Path) {
    val file = directory.resolve("edited.kt")
    val original = "fun first() {\n  assertCompiler(\n    source = \"first\",\n  )\n}\nfun second() {\n  assertCompiler(\n    source = \"second\",\n  )\n}"
    Files.writeString(file, original)
    InlineCompilerSnapshots.write(
      file, 2, mapOf(CompilerSnapshotKind.FIR to "first"), "2.4.21", false,
      expectedSource = original, compiledSourceLine = true,
    )
    val edited = Files.readString(file) + "\n// user edit\n"
    Files.writeString(file, edited)
    assertFailsWith<IllegalStateException> {
      InlineCompilerSnapshots.write(
        file, 7, mapOf(CompilerSnapshotKind.FIR to "second"), "2.4.21", false,
        expectedSource = original, compiledSourceLine = true,
      )
    }
    assertEquals(edited, Files.readString(file))
  }

  @Test
  fun `records both missing phases and preserves unrelated source`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val source = ("""
      fun test() {
        assertCompiler(
          directory = temp,
          source = """ + "\"\"\"" + """
            val text = "assertCompiler(irrelevant)"
          """ + "\"\"\"" + """,
        )
      }
    """).trimIndent()
    Files.writeString(file, source)
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "fir output", CompilerSnapshotKind.IR to "ir output"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "fir = SnapshotExpectation(")
    assertContains(updated, "ir = SnapshotExpectation(")
    assertContains(updated, "fir output")
    assertContains(updated, "ir output")
    assertContains(updated, "assertCompiler(irrelevant)")
  }

  @Test
  fun `records specific version while preserving other versions and default`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.4.20" to "older", "2.4.21" to "old")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "new"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "\"shared\"")
    assertContains(updated, "\"2.4.20\" to \"older\"")
    assertContains(updated, "\"2.4.21\" to \"\"\"")
    assertContains(updated, "new")
  }

  @Test
  fun `adds a version override and safely encodes dollar signs`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          ir = SnapshotExpectation("shared"),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.IR to "class Example${'$'}Nested"), "2.4.20", true)
    val updated = Files.readString(file)
    assertContains(updated, "versions = mapOf(\"2.4.20\" to")
    assertContains(updated, "class Example${'$'}{'${'$'}'}Nested")
    assertContains(updated, "\"shared\"")
  }

  @Test
  fun `replaces only the selected missing phase`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = snapshot(),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "fir output"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "fir = SnapshotExpectation(")
    assertEquals(false, "\n    ir =" in updated)
  }

  @Test
  fun `refuses unsupported expectation without changing source`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val original = "fun test() { assertCompiler(source = \"source\", fir = expected) }"
    Files.writeString(file, original)
    assertFailsWith<IllegalStateException> {
      InlineCompilerSnapshots.write(file, 1, mapOf(CompilerSnapshotKind.FIR to "output"), "2.4.21", false)
    }
    assertEquals(original, Files.readString(file))
  }

  @Test
  fun `refuses ambiguous calls on the same line`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val original = "fun test() { assertCompiler(source = \"a\"); assertCompiler(source = \"b\") }"
    Files.writeString(file, original)
    assertFailsWith<IllegalArgumentException> {
      InlineCompilerSnapshots.write(file, 1, mapOf(CompilerSnapshotKind.FIR to "output"), "2.4.21", false)
    }
    assertEquals(original, Files.readString(file))
  }
  @Test
  fun `qualified nested calls record without changing comments or neighboring calls`(@TempDir directory: Path) {
    val file = directory.resolve("Qualified.kt")
    val original = """
      fun first() {
        // Keep this comment and the unrelated string: assertCompiler(source = "ignored")
        run {
          dev.knapshots.assertCompiler(
            source = "first", // trailing comment
            fir = snapshot(),
          )
        }
      }
      fun second() { assertCompiler(source = "untouched") }
    """.trimIndent()
    Files.writeString(file, original)
    InlineCompilerSnapshots.write(file, 5, mapOf(CompilerSnapshotKind.FIR to "output"), "2.4.21", false)
    val recorded = Files.readString(file)
    assertContains(recorded, "// Keep this comment and the unrelated string")
    assertContains(recorded, "source = \"first\", // trailing comment")
    assertContains(recorded, "dev.knapshots.assertCompiler(")
    assertContains(recorded, "fun second() { assertCompiler(source = \"untouched\") }")
    assertContains(recorded, "fir = SnapshotExpectation(")
  }

  @Test
  fun `recorded strings with delimiters dollars unicode and backslashes compile and round trip`(@TempDir directory: Path) {
    val outputs = listOf(
      "Café 漢字 😀 / dollar ${'$'}name / slash \\",
      "triple delimiter: \"\"\" / dollar ${'$'}{value} / slash \\\\nsecond line",
      "first line\n\nthird line",
      "first line\r\nsecond line\rthird line",
    )
    outputs.forEachIndexed { index, output ->
      val file = directory.resolve("Recorded$index.kt")
      Files.writeString(file, """
        var observed = ""
        object Helper { fun fir(expected: String) { observed = expected } }
        fun result(): String {
          Helper.fir()
          return observed
        }
      """.trimIndent())
      recordInlineSnapshots(InlineSnapshotLocation(file, 4), "fir", mapOf("expected" to output),
        org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION)
      val result = EmbeddedCompilerHost().compile(CompilerRequest(
        listOf(CompilerSource("Recorded$index.kt", Files.readString(file))),
        org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION, emptySet(),
      ))
      assertTrue(result.succeeded, result.diagnostics.toString())
      val observed = result.classLoader().loadClass("Recorded${index}Kt").getMethod("result").invoke(null) as String
      assertEquals(output, dev.knapshots.testing.snapshot.trimSnapshotIndent(observed))
    }
  }

  @Test
  fun `a failed batch leaves every requested snapshot unchanged`(@TempDir directory: Path) {
    val file = directory.resolve("Atomic.kt")
    val source = "fun test() {\n  fir()\n  ir(dynamicExpectation)\n}\n"
    Files.writeString(file, source)
    assertFailsWith<IllegalStateException> {
      recordInlineSnapshotBatch(listOf(
        InlineSnapshotUpdate(InlineSnapshotLocation(file, 2), "fir", mapOf("expected" to "new FIR"), source),
        InlineSnapshotUpdate(InlineSnapshotLocation(file, 3), "ir", mapOf("expected" to "new IR"), source),
      ), "2.4.21")
    }
    assertEquals(source, Files.readString(file))
  }

  @Test fun `recording preserves CRLF source file line endings`(@TempDir directory: Path) {
    val file = directory.resolve("Windows.kt")
    val source = "fun test() {\r\n  fir()\r\n}\r\n"
    Files.writeString(file, source)
    recordInlineSnapshots(InlineSnapshotLocation(file, 2), "fir", mapOf("expected" to "first\nsecond"), "2.4.21")
    val recorded = Files.readString(file)
    assertEquals(false, Regex("(?<!\r)\n").containsMatchIn(recorded), recorded)
    assertContains(recorded, "first")
    assertContains(recorded, "second")
    val compiled = EmbeddedCompilerHost().compile(CompilerRequest(
      listOf(CompilerSource("Windows.kt", "fun fir(expected: String) {}\r\n" + recorded)),
      org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION, emptySet(),
    ))
    assertTrue(compiled.succeeded, compiled.diagnostics.toString())
  }

}
