package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the real compiler-plugin boundary without another project checkout. */
class FixturePluginIntegrationTest {
  private val version = KotlinCompilerVersion.VERSION
  private val source = """
    package fixture
    annotation class AddMember
    @AddMember class Example
    fun value(): String = "knapshots:input"
  """.trimIndent()
  private val host = EmbeddedCompilerHost(
    compilerArguments = listOf("-Xplugin=${checkNotNull(System.getProperty("knapshots.fixturePluginJar"))}"),
    captureArguments = { captures -> captures.flatMap { (kind, path) ->
      val option = when (kind) {
        CompilerSnapshotKind.FIR -> "fir"
        CompilerSnapshotKind.IR -> "ir"
        else -> error("Unsupported fixture capture: ${kind.name}")
      }
      listOf("-P", "plugin:dev.knapshots.fixture:$option=$path")
    } },
  )

  @Test fun `input only records the hosts available compiler output`(@TempDir directory: Path) {
    val file = directory.resolve("InputOnly.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"source\")\n}\n")
    val automatic = object : CompilerHost {
      override fun defaultSnapshotKinds() = setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR)
      override fun compile(request: CompilerRequest) = host.compile(request)
    }
    assertFailsWith<AssertionError> {
      assertCompiler(source, version, automatic, record = SnapshotRecording.MISSING, location = InlineSnapshotLocation(file, 2))
    }
    val recorded = Files.readString(file)
    assertContains(recorded, "fir(")
    assertContains(recorded, "FIR classes")
    assertContains(recorded, "ir(")
    assertContains(recorded, "IR replacements")
    assertContains(recorded, "diagnostics { snapshot(")
    val stubs = "class Diagnostics { fun snapshot(value: String) {} }\n" +
      "class Expectations { fun fir(value: String) {} ; fun ir(value: String) {} ; fun diagnostics(block: Diagnostics.() -> Unit) {} }\n" +
      "fun assertCompiler(source: String, block: Expectations.() -> Unit) {}\n"
    val rewritten = EmbeddedCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Rewritten.kt", stubs + recorded)), version, emptySet()))
    assertTrue(rewritten.succeeded, rewritten.diagnostics.toString())
    assertCompiler(source, version, automatic, record = SnapshotRecording.NONE) {
      fir("FIR classes\nfixture.AddMember\nfixture.Example")
      ir("IR replacements: 1\nIR generated members: 1")
      diagnostics { snapshot("") }
    }
  }

  @Test fun `captures real FIR and IR and executes transformed bytecode`() {
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Example.kt", source)), version,
      setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR)))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("FIR classes\nfixture.AddMember\nfixture.Example\n", result.captures[CompilerSnapshotKind.FIR])
    assertEquals("IR replacements: 1\nIR generated members: 1\n", result.captures[CompilerSnapshotKind.IR])
    assertEquals("knapshots:output", result.classLoader().loadClass("fixture.ExampleKt").getMethod("value").invoke(null))
    assertCompiler(source, version, host, fir = SnapshotExpectation("FIR classes\nfixture.AddMember\nfixture.Example"),
      ir = SnapshotExpectation("IR replacements: 1\nIR generated members: 1"))
  }

  @Test fun `records actual plugin captures inline`(@TempDir directory: Path) {
    val file = directory.resolve("FixtureTest.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(source = \"example\", fir = snapshot(), ir = snapshot())\n}\n")
    val failure = assertFailsWith<AssertionError> {
      assertCompiler(source, version, host, fir = snapshot(), ir = snapshot(), record = SnapshotRecording.MISSING,
        location = InlineSnapshotLocation(file, 2))
    }
    assertContains(failure.message.orEmpty(), "Recorded inline snapshots")
    val recorded = Files.readString(file)
    assertContains(recorded, "fixture.Example")
    assertContains(recorded, "IR replacements: 1")
  }

  @Test fun `compiler errors stay observable with the plugin installed`() {
    assertCompiler("class Broken { val x: Missing = TODO() }", version, host) {
      fails()
      diagnostics { error("Unresolved reference") }
    }
  }
  @Test fun `reports a diagnostic from the fixture plugin`() {
    val diagnosticHost = EmbeddedCompilerHost(compilerArguments = listOf(
      "-Xplugin=${checkNotNull(System.getProperty("knapshots.fixturePluginJar"))}",
      "-P", "plugin:dev.knapshots.fixture:diagnostic=Fixture plugin warning",
    ))
    assertCompiler("class Example", version, diagnosticHost) {
      succeeds()
      diagnostics { warning("Fixture plugin warning") }
    }
  }
  @Test fun `compiler output changes only when the fixture plugin is loaded`() {
    val result = EmbeddedCompilerHost().compile(CompilerRequest(
      listOf(CompilerSource("Example.kt", source)), version, emptySet(),
    ))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("knapshots:input", result.classLoader().loadClass("fixture.ExampleKt").getMethod("value").invoke(null))
  }
  @Test fun `generated member is visible to type checking and runs`() {
    val input = "package fixture\nannotation class AddMember\n@AddMember class Example\nfun generated() = Example().addedMember()"
    val request = CompilerRequest(listOf(CompilerSource("Generated.kt", input)), version, emptySet())
    val result = host.compile(request)
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("generated by fixture", result.classLoader().loadClass("fixture.GeneratedKt").getMethod("generated").invoke(null))
    assertCompiler(input, version, host,
      fir = SnapshotExpectation("FIR classes\nfixture.AddMember\nfixture.Example"),
      ir = SnapshotExpectation("IR replacements: 0\nIR generated members: 1"),
    )
    val withoutPlugin = EmbeddedCompilerHost().compile(request)
    assertTrue(!withoutPlugin.succeeded, "Generated member should require the fixture plugin")
    assertTrue(withoutPlugin.diagnostics.any { it.message.contains("Unresolved reference") })
  }
  @Test fun `type checker rejects incorrect use of a generated member`() {
    val input = "package fixture\nannotation class AddMember\n@AddMember class Example\nval value: Int = Example().addedMember()"
    assertCompiler(input, version, host) {
      fails()
      diagnostics { error("type mismatch") }
    }
  }

  @Test fun `version-specific snapshots use real plugin output`() {
    assertCompiler(source, version, host,
      fir = SnapshotExpectation("incorrect default", versions = mapOf(version to "FIR classes\nfixture.AddMember\nfixture.Example")),
      ir = SnapshotExpectation("incorrect default", versions = mapOf(version to "IR replacements: 1\nIR generated members: 1")),
    )
  }

  @Test fun `additional sources can reference a generated member`() {
    val result = host.compile(CompilerRequest(listOf(
      CompilerSource("Model.kt", "package fixture\nannotation class AddMember\n@AddMember class Example"),
      CompilerSource("Use.kt", "package fixture\nfun generated() = Example().addedMember()"),
    ), version, emptySet()))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("generated by fixture", result.classLoader().loadClass("fixture.UseKt").getMethod("generated").invoke(null))
  }
  @Test fun `unannotated classes do not receive the generated member`() {
    val input = "package fixture\nclass Plain\nfun value() = Plain().addedMember()"
    assertCompiler(input, version, host) {
      fails()
      diagnostics { error("Unresolved reference") }
    }
  }

  @Test fun `captures warnings and source locations from real compilation`() {
    val input = "@Deprecated(\"Use the replacement\") fun old() = 1\nval value = old()"
    assertCompiler(input, version, host) {
      succeeds()
      diagnostics { warning("Use the replacement", location = "Main.kt:2") }
    }
  }

  @Test fun `compiler flags affect whether a warning fails compilation`() {
    val input = "@Deprecated(\"Use the replacement\") fun old() = 1\nval value = old()"
    val strict = EmbeddedCompilerHost(compilerArguments = listOf("-Werror"))
    assertCompiler(input, version, strict) {
      fails()
      diagnostics {
        warning("Use the replacement", location = "Main.kt:2")
        error("warnings found and -Werror specified")
      }
    }
  }
  @Test fun `generated members work on nested and generic classes`() {
    val input = """
      package fixture
      annotation class AddMember
      class Outer { @AddMember class Nested }
      @AddMember class Box<T>(val value: T)
      fun result() = Outer.Nested().addedMember() + Box(1).addedMember()
    """.trimIndent()
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Model.kt", input)), version, emptySet()))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("generated by fixturegenerated by fixture", result.classLoader().loadClass("fixture.ModelKt").getMethod("result").invoke(null))
  }

  @Test fun `multiple compiler errors preserve their source locations`() {
    val input = "class Broken {\n  val first: Missing = TODO()\n  val second: Unknown = TODO()\n}"
    assertCompiler(input, version, host) {
      fails()
      diagnostics {
        error("Unresolved reference", location = "Main.kt:2")
        error("Unresolved reference", location = "Main.kt:3")
      }
    }
  }
  @Test fun `generated declarations remain available to a plugin-free downstream compilation`(@TempDir directory: Path) {
    val producer = host.compile(CompilerRequest(listOf(CompilerSource("Model.kt", """
      package fixture
      annotation class AddMember
      @AddMember class Example
    """.trimIndent())), version, emptySet()))
    assertTrue(producer.succeeded, producer.diagnostics.toString())
    producer.classFiles.forEach { (name, bytes) ->
      val file = directory.resolve(name)
      Files.createDirectories(file.parent)
      Files.write(file, bytes)
    }
    val consumer = EmbeddedCompilerHost(classpath = listOf(directory)).compile(CompilerRequest(listOf(
      CompilerSource("Consumer.kt", "package consumer\nfun value() = fixture.Example().addedMember()"),
    ), version, emptySet()))
    assertTrue(consumer.succeeded, consumer.diagnostics.toString())
    URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { parent ->
      assertEquals("generated by fixture", consumer.classLoader(parent).loadClass("consumer.ConsumerKt").getMethod("value").invoke(null))
    }
  }
  @Test fun `records FIR and IR together from the trailing block`(@TempDir directory: Path) {
    val file = directory.resolve("Phases.kt")
    Files.writeString(file, "fun test() {\n  assertCompiler(\"source\") {\n    fir()\n    ir()\n    diagnostics()\n  }\n}\n")
    val failure = assertFailsWith<AssertionError> {
      assertCompiler(source, version, host, record = SnapshotRecording.MISSING) {
        succeeds()
        fir(location = InlineSnapshotLocation(file, 3))
        ir(location = InlineSnapshotLocation(file, 4))
        diagnostics(InlineSnapshotLocation(file, 5))
      }
    }
    assertContains(failure.message.orEmpty(), "Recorded compiler snapshots")
    val output = Files.readString(file)
    assertContains(output, "FIR classes")
    assertContains(output, "IR replacements")
    assertContains(output, "diagnostics { snapshot(")
  }

  @Test fun `records a generic phase expectation`(@TempDir directory: Path) {
    val file = directory.resolve("GenericPhase.kt")
    Files.writeString(file, "fun test() {\n  snapshot(CompilerSnapshotKind.FIR, snapshot())\n}\n")
    assertFailsWith<AssertionError> {
      assertCompiler(source, version, host, record = SnapshotRecording.MISSING) {
        succeeds()
        snapshot(CompilerSnapshotKind.FIR, snapshot(), InlineSnapshotLocation(file, 2))
      }
    }
    assertContains(Files.readString(file), "FIR classes")
  }

  @Test fun `plugin errors preserve locations for multiple declarations`() {
    val input = """
      package fixture
      annotation class Reject
      annotation class Caution
      @Reject class First
      @Caution class Cautious
      @Reject class Second
    """.trimIndent()
    assertCompiler(input, version, host) {
      fails()
      diagnostics {
        error("Fixture rejects First", line = 4)
        error("Fixture rejects Second", location = "Main.kt:6")
      }
    }
  }
  @Test fun `plugin warning preserves its source location`() {
    val input = "package fixture\nannotation class Caution\n@Caution class Cautious"
    assertCompiler(input, version, host) {
      succeeds()
      diagnostics { warning("Fixture warns about Cautious", location = "Main.kt:3") }
    }
  }
  @Test fun `generated properties resolve and execute`() {
    val input = "package fixture\nannotation class AddProperty\n@AddProperty class Example\nfun value() = Example().addedProperty"
    val request = CompilerRequest(listOf(CompilerSource("Property.kt", input)), version, emptySet())
    val result = host.compile(request)
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertEquals("generated property", result.classLoader().loadClass("fixture.PropertyKt").getMethod("value").invoke(null))
    assertCompiler(input, version, host,
      fir = SnapshotExpectation("FIR classes\nfixture.AddProperty\nfixture.Example"),
      ir = SnapshotExpectation("IR replacements: 0\nIR generated properties: 1"),
    )
    assertTrue(!EmbeddedCompilerHost().compile(request).succeeded)
  }
  @Test fun `plugin diagnostic identifies its precise column`() {
    val input = "package fixture\nannotation class Reject\n@Reject class First"
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Main.kt", input)), version, emptySet()))
    val diagnostic = result.diagnostics.single { it.message.contains("Fixture rejects First") }
    assertEquals("Main.kt:3", diagnostic.location)
    assertEquals(9, diagnostic.column)
  }
  @Test fun `plugin errors in different files preserve their locations`() {
    val sources = listOf(
      CompilerSource("Annotations.kt", "package fixture\nannotation class Reject\nannotation class Caution"),
      CompilerSource("Bad.kt", "package fixture\n@Reject class Invalid"),
      CompilerSource("Other.kt", "package fixture\n@Reject class Other"),
    )
    val result = host.compile(CompilerRequest(sources, version, emptySet()))
    assertTrue(!result.succeeded)
    assertTrue(result.diagnostics.any { it.severity == CompilerDiagnosticSeverity.ERROR && it.message.contains("Fixture rejects Invalid") && it.location == "Bad.kt:2" }, result.diagnostics.toString())
    assertTrue(result.diagnostics.any { it.severity == CompilerDiagnosticSeverity.ERROR && it.message.contains("Fixture rejects Other") && it.location == "Other.kt:2" }, result.diagnostics.toString())
    val rendered = renderCompilerDiagnostics(sources, result.diagnostics)
    assertContains(rendered, "// FILE: Bad.kt")
    assertContains(rendered, "error: ")
    assertContains(rendered, "// FILE: Other.kt")
    assertContains(rendered, "Fixture rejects Other")
  }

  @Test fun `private generated declarations remain unavailable to another file`() {
    val sources = listOf(
      CompilerSource("Producer.kt", "package fixture\nannotation class AddMember\n@AddMember private class Example"),
      CompilerSource("Consumer.kt", "package fixture\nfun value() = Example().addedMember()"),
    )
    val result = host.compile(CompilerRequest(sources, version, emptySet()))
    assertTrue(!result.succeeded)
    assertTrue(result.diagnostics.any { it.severity == CompilerDiagnosticSeverity.ERROR && it.location == "Consumer.kt:2" }, result.diagnostics.toString())
  }

  @Test fun `plugin warnings from multiple files are captured on successful compilation`() {
    val sources = listOf(
      CompilerSource("Annotations.kt", "package fixture\nannotation class Caution"),
      CompilerSource("First.kt", "package fixture\n@Caution class First"),
      CompilerSource("Second.kt", "package fixture\n@Caution class Second"),
    )
    val result = host.compile(CompilerRequest(sources, version, emptySet()))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertTrue(result.diagnostics.any { it.severity == CompilerDiagnosticSeverity.WARNING && it.message.contains("Fixture warns about First") && it.location == "First.kt:2" }, result.diagnostics.toString())
    assertTrue(result.diagnostics.any { it.severity == CompilerDiagnosticSeverity.WARNING && it.message.contains("Fixture warns about Second") && it.location == "Second.kt:2" }, result.diagnostics.toString())
  }

  @Test fun `versioned snapshot DSL compares real fixture plugin output`() {
    assertCompiler(source, version, host, record = SnapshotRecording.NONE) {
      fir {
        default("incorrect default")
        versioned(version.substringBeforeLast('.') + ".*", "FIR classes\nfixture.AddMember\nfixture.Example")
      }
    }
  }

  @Test fun `real fixture selects the narrower compiler family`() {
    val components = version.substringBefore('-').split('.')
    val major = components[0]
    val minor = components[1].toInt()
    val nextPatch = components[2].toInt() + 1
    assertCompiler(source, version, host, record = SnapshotRecording.NONE) {
      fir {
        default("wrong default")
        versioned("$major.$minor.*", "wrong broad family")
        versioned(">=$version <$major.$minor.$nextPatch", "FIR classes\nfixture.AddMember\nfixture.Example")
      }
    }
  }

}
