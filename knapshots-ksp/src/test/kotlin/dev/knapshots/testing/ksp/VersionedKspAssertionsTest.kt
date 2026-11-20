package dev.knapshots.testing.ksp

import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.SnapshotRecording
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class VersionedKspAssertionsTest {
  private val host = KspHost { request ->
    KspResult(request.compilerVersion.startsWith("2.5"), mapOf("Out.kt" to "version ${request.compilerVersion}"))
  }

  @Test fun `top-level version branches override output and outcome separately`() {
    assertKsp("class A", "2.5.0", host, record = SnapshotRecording.NONE) {
      generated("// FILE: Out.kt\nversion 2.5.0")
      default { fails() }
      versioned("2.5.*") { succeeds() }
    }
    assertKsp("class A", "2.4.21", host, record = SnapshotRecording.NONE) {
      generated("// FILE: Out.kt\nversion 2.4.21")
      default { fails() }
      versioned("2.5.*") { succeeds() }
    }
  }

  @Test fun `nested versioned generated and diagnostics snapshots select independently`() {
    val compiler = KspHost { KspResult(true, mapOf("Out.kt" to "output"), diagnostics = listOf(KspDiagnostic(KspDiagnosticSeverity.WARNING, "warning", "src/main/kotlin/Main.kt", 1, 1))) }
    assertKsp("input", "2.5.0", compiler, record = SnapshotRecording.NONE) {
      generated { default("wrong"); versioned("2.5.*", "// FILE: Out.kt\noutput") }
      diagnostics { default("wrong"); versioned("2.5.*", "input\n^\nwarning: warning") }
    }
  }

  @Test fun `records only the selected generated placeholder`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, "fun test() {\n  generated {\n    default()\n    versioned(\"2.5.*\")\n    versioned(\"2.6.*\")\n  }\n}\n")
    val error = assertFailsWith<AssertionError> {
      assertKsp("input", "2.5.0", host, record = SnapshotRecording.MISSING) {
        generated { default(location = InlineSnapshotLocation(file, 3)); versioned("2.5.*", location = InlineSnapshotLocation(file, 4)); versioned("2.6.*") }
      }
    }
    assertContains(error.message.orEmpty(), "Recorded")
    val output = Files.readString(file)
    assertContains(output, "default()")
    assertContains(output, "versioned(\"2.6.*\")")
    assertContains(output, "versioned(\"2.5.*\", \"\"\"")
    assertContains(output, "version 2.5.0")
  }

  @Test fun `duplicate default output fails before running the processor`() {
    val never = KspHost { error("must not run") }
    assertFailsWith<IllegalStateException> {
      assertKsp("input", "2.4.21", never, record = SnapshotRecording.NONE) {
        generated("one")
        default { generated("two") }
      }
    }
  }
  @Test fun `overridden default placeholder does not require a source file`(@TempDir directory: Path) {
    val nonexistent = InlineSnapshotLocation(directory.resolve("missing.kt"), 1)
    assertKsp("input", "2.5.0", host, record = SnapshotRecording.MISSING) {
      generated(location = nonexistent)
      versioned("2.5.*") { generated("// FILE: Out.kt\nversion 2.5.0") }
    }
  }

  @Test fun `narrow compiler family is selected for generated source`() {
    assertKsp("input", "2.4.21", host, record = SnapshotRecording.NONE) {
      generated {
        default("wrong default")
        versioned("2.4.*", "wrong broad family")
        versioned(">=2.4.20 <2.5.0", "// FILE: Out.kt\nversion 2.4.21")
      }
    }
  }

  @Test fun `recording targets the narrower matching compiler family`(@TempDir directory: Path) {
    val file = directory.resolve("NarrowKsp.kt")
    Files.writeString(file, "fun test() {\n  generated {\n    default()\n    versioned(\"2.4.*\")\n    versioned(\">=2.4.20 <2.5.0\")\n  }\n}\n")
    assertFailsWith<AssertionError> {
      assertKsp("input", "2.4.21", host, record = SnapshotRecording.MISSING) {
        generated { default(); versioned("2.4.*", location = InlineSnapshotLocation(file, 4)); versioned(">=2.4.20 <2.5.0", location = InlineSnapshotLocation(file, 5)) }
      }
    }
    val output = Files.readString(file)
    assertContains(output, "versioned(\"2.4.*\")")
    assertContains(output, "versioned(\">=2.4.20 <2.5.0\", \"\"\"")
    assertContains(output, "version 2.4.21")
  }

}
