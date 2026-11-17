package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.assertEditor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A real imported-project smoke test. It is intentionally excluded from ordinary `check`: launch
 * it with the `ide-integration` tag and an explicit `-Pknapshots.idePath`.
 */
@Tag("ide-integration")
class IntellijAdapterIntegrationTest {
  @Test fun `adapter resolves a reference in an imported Kotlin project`() {
    val repository = Path.of(checkNotNull(System.getProperty("knapshots.projectDir")))
    val configuredIde = System.getProperty("knapshots.idePath")?.let(Path::of)
    assumeTrue(configuredIde != null, "Set -Pknapshots.idePath to run the IntelliJ adapter integration test.")
    val ide = checkNotNull(configuredIde)

    val javaHome = ide.resolve("Contents/jbr/Contents/Home")
    assumeTrue(
      javaHome.resolve("bin/java").toFile().canExecute(),
      "The current smoke test supports an Android Studio macOS bundle.",
    )
    val template = EditorProjectTemplate(
      settingsGradleKts = """
        pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { mavenCentral() } }
        rootProject.name = "knapshots-editor-consumer"
      """.trimIndent(),
      buildGradleKts = """
        plugins { kotlin("jvm") version "2.4.21" }
        repositories { mavenCentral() }
      """.trimIndent(),
    )
    val launcher = CommandEditorProcessLauncher(
      command = { project ->
        listOf(
          repository.resolve("gradlew").toString(),
          "--no-daemon",
          "-p",
          repository.toString(),
          "-Pknapshots.enableIntellijAdapter",
          "-Pknapshots.idePath=$ide",
          "-Pknapshots.editor.project=${project.directory}",
          "-Pknapshots.editor.request=${project.requestPath}",
          "-Pknapshots.editor.response=${project.responsePath}",
          ":knapshots-intellij:runIde",
          "--args=${project.directory}",
        )
      },
      timeout = Duration.ofMinutes(12),
      environment = mapOf("JAVA_HOME" to javaHome.toString()),
    )
    val host = RunnerEditorSourceHost(
      template = template,
      launcher = launcher,
      // Do not use JUnit's temporary directory: it removes failed projects after the test and
      // defeats the runner's retained-project debugging contract.
      temporaryParent = Files.createDirectories(repository.resolve("build/ide-integration")),
      retention = EditorProjectRetention.ON_FAILURE,
    )

    assertEditor(
      source = """
        object Values {
          val number = 1
        }

        val doubled = Values.number
      """,
      host = host,
      checkDiagnostics = false,
    ) {
      reference("Values.number").resolves()
    }
  }
}
