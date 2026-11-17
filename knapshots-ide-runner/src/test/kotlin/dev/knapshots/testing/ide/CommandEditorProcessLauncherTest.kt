package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CommandEditorProcessLauncherTest {
  private val template = EditorProjectTemplate(
    settingsGradleKts = "rootProject.name = \"editor-fixture\"",
    buildGradleKts = "plugins { kotlin(\"jvm\") version \"2.4.21\" }",
  )

  @Test fun `runs commands in the generated project with request environment`(@TempDir directory: Path) {
    val project = EditorProjectMaterializer().materialize(
      directory.resolve("project"),
      template,
      EditorRequest(listOf(EditorSource("Example.kt", "class Example")), emptySet(), false),
    )
    val launcher = CommandEditorProcessLauncher(
      command = {
        listOf(javaExecutable().toString(), "-version")
      },
      timeout = Duration.ofSeconds(10),
      environment = mapOf("KNAPSHOTS_TEST_VALUE" to "present"),
    )

    val result = launcher.launch(project)

    assertEquals(0, result.exitCode)
    val log = project.directory.resolve(".knapshots/ide-process.log")
    assertTrue(Files.isRegularFile(log))
    assertContains(Files.readString(log), "version")
  }

  @Test fun `nonzero exits retain a useful log location`(@TempDir directory: Path) {
    val project = EditorProjectMaterializer().materialize(
      directory.resolve("project"),
      template,
      EditorRequest(listOf(EditorSource("Example.kt", "class Example")), emptySet(), false),
    )
    val launcher = CommandEditorProcessLauncher(
      command = { listOf(javaExecutable().toString(), "-definitely-not-a-java-option") },
      timeout = Duration.ofSeconds(10),
    )

    val result = launcher.launch(project)

    assertTrue(result.exitCode != 0)
    assertContains(result.summary.orEmpty(), "ide-process.log")
  }

  private fun javaExecutable(): Path = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
  )
}
