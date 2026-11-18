package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
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

  @Test fun `timeout stops the launched child process`(@TempDir directory: Path) {
    org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    val project = EditorProjectMaterializer().materialize(
      directory.resolve("project"), template,
      EditorRequest(listOf(EditorSource("Example.kt", "class Example")), emptySet(), false),
    )
    val launcher = CommandEditorProcessLauncher(
      command = { listOf("/bin/sh", "-c", "sleep 60 & echo \$! > child.pid; wait") },
      timeout = Duration.ofSeconds(2),
    )
    val result = launcher.launch(project)
    val pid = Files.readString(project.directory.resolve("child.pid")).trim().toLong()
    assertEquals(-1, result.exitCode)
    assertContains(result.summary.orEmpty(), "timed out")
    val child = ProcessHandle.of(pid)
    if (child.isPresent) child.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse(child.isPresent && child.get().isAlive)
  }

  private fun javaExecutable(): Path = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
  )
}
