package dev.knapshots.testing.compiler

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertTrue

/** Opt-in integration with a separately built KPaths plugin and its runtime artifacts. */
@EnabledIfSystemProperty(named = "knapshots.kpathsCheckout", matches = ".+")
class KPathsCompilerIntegrationTest {
  @Test fun `compiles a pathable class and captures compiler plugin output`(@TempDir directory: Path) {
    val root = Path.of(System.getProperty("knapshots.kpathsCheckout"))
    fun artifact(module: String) = root.resolve("$module/build/libs/$module-jvm-0.1.0.jar")
    val host = EmbeddedCompilerHost(
      classpath = listOf(artifact("kpaths-core"), artifact("kpaths-annotations")),
      compilerArguments = listOf("-Xplugin=${artifact("kpaths-compiler-plugin")}"),
      captureArguments = { captures -> captures.flatMap { (kind, path) ->
        val option = when (kind) { CompilerSnapshotKind.FIR -> "firSnapshot"; CompilerSnapshotKind.IR -> "irSnapshot"; else -> error("Unsupported capture") }
        listOf("-P", "plugin:dev.kpaths.compiler-plugin:$option=$path")
      } },
    )
    val result = host.compile(CompilerRequest(listOf(CompilerSource("Example.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String)
      val path = Person.Paths.name
    """.trimIndent())), KotlinCompilerVersion.VERSION, setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR)))
    assertTrue(result.succeeded, result.diagnostics.toString())
    assertTrue(result.captures[CompilerSnapshotKind.FIR]?.contains("Person") == true, result.captures.toString())
    assertTrue(result.captures[CompilerSnapshotKind.IR]?.contains("Person") == true, result.captures.toString())
    val testSource = directory.resolve("ExampleTest.kt")
    Files.writeString(testSource, "fun test() {\n  assertCompiler(source = \"example\", fir = snapshot(), ir = snapshot())\n}\n")
    assertFailsWith<AssertionError> {
      assertCompiler("import dev.kpaths.annotations.Pathable\n@Pathable data class Person(val name: String)",
        KotlinCompilerVersion.VERSION, host, fir = snapshot(), ir = snapshot(), record = SnapshotRecording.MISSING,
        location = InlineSnapshotLocation(testSource, 2))
    }
    assertContains(Files.readString(testSource), "SnapshotExpectation(")
  }
}
