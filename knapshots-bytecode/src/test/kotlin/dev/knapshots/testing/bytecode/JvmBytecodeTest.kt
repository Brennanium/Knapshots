package dev.knapshots.testing.bytecode

import dev.knapshots.testing.snapshot.assertSnapshot
import dev.knapshots.testing.compiler.EmbeddedCompilerHost
import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerSource
import dev.knapshots.testing.compiler.CompilerHost
import dev.knapshots.testing.compiler.CompilerResult
import dev.knapshots.testing.compiler.CompilerSnapshotKind
import dev.knapshots.testing.compiler.assertCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BytecodeSubject { fun uppercase(value: String): String = value.uppercase() }

class JvmBytecodeTest {
  private fun subjectBytes(): ByteArray = checkNotNull(BytecodeSubject::class.java.getResourceAsStream("BytecodeSubject.class")).use { it.readBytes() }

  @Test fun `reads superclass and method calls from class bytes`() {
    val bytecode = ClassBytecode.read(subjectBytes())
    assertEquals("java/lang/Object", bytecode.superName)
    val method = bytecode.method("uppercase", "(Ljava/lang/String;)Ljava/lang/String;")
    assertTrue(method.methodCalls.any { it.name == "toUpperCase" })
    assertTrue("java/lang/String" in bytecode.references)
  }

  @Test fun `bytecode snapshot composes with compiler captures`() {
    val kind = JVM_BYTECODE
    val bytes = subjectBytes()
    val host = CompilerHost { request ->
      assertEquals(setOf(CompilerSnapshotKind.FIR), request.captures)
      CompilerResult(true, captures = mapOf(CompilerSnapshotKind.FIR to "fir"),
        classFiles = mapOf("dev/knapshots/testing/bytecode/BytecodeSubject.class" to bytes))
    }.withJvmBytecodeSnapshot("dev.knapshots.testing.bytecode.BytecodeSubject")
    assertCompiler("class Example", "2.4.21", host) {
      succeeds()
      fir("fir")
      bytecode(renderJvmBytecode(bytes))
    }
  }

  @Test fun `captures bytecode from an actual compilation`() {
    val host = EmbeddedCompilerHost().withJvmBytecodeSnapshot("fixture.Greeting")
    val result = host.compile(CompilerRequest(
      listOf(CompilerSource("Greeting.kt", "package fixture\nclass Greeting { fun greet() = \"hello\" }")),
      KotlinVersion.CURRENT.toString(),
      setOf(JVM_BYTECODE),
    ))
    assertTrue(result.succeeded, result.diagnostics.toString())
    val snapshot = checkNotNull(result.captures[JVM_BYTECODE])
    assertTrue(snapshot.contains("fixture/Greeting"))
    assertTrue(snapshot.contains("LDC \"hello\""))
  }

  @Test fun `renders deterministic bytecode without source lines`() {
    val bytes = subjectBytes()
    val rendered = renderJvmBytecode(bytes)
    assertTrue(rendered.contains("uppercase"))
    assertFalse(rendered.contains("LINENUMBER"))
    assertEquals(rendered, renderJvmBytecode(bytes))
    assertSnapshot(bytes, rendered, jvmBytecodeSnapshotting)
    assertFailsWith<AssertionError> { assertSnapshot(bytes, "wrong", jvmBytecodeSnapshotting) }
  }
  @Test fun `captures bytecode transformed by the fixture plugin`() {
    val host = EmbeddedCompilerHost(compilerArguments = listOf(
      "-Xplugin=${checkNotNull(System.getProperty("knapshots.fixturePluginJar"))}",
    )).withJvmBytecodeSnapshot("fixture.ExampleKt")
    val result = host.compile(CompilerRequest(
      listOf(CompilerSource("Example.kt", "package fixture\nfun value() = \"knapshots:input\"")),
      KotlinVersion.CURRENT.toString(), setOf(JVM_BYTECODE),
    ))
    assertTrue(result.succeeded, result.diagnostics.toString())
    val output = checkNotNull(result.captures[JVM_BYTECODE])
    assertTrue(output.contains("knapshots:output"))
    assertFalse(output.contains("knapshots:input"))
  }
}
