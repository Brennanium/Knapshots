package dev.knapshots.testing.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class GradlePreflightLauncherTest {
  @TempDir lateinit var directory: Path

  private fun project(): MaterializedEditorProject = MaterializedEditorProject(
    directory, directory.resolve("request"), directory.resolve("response"),
  )

  @Test fun `successful preflight launches IDE`() {
    var launched = false
    val launcher = GradlePreflightLauncher(
      EditorProcessLauncher { launched = true; EditorProcessResult(0) }, directory,
      command = { listOf("/bin/sh", "-c", "printf preflight-ok") },
    )
    assertEquals(0, launcher.launch(project()).exitCode)
    assertTrue(launched)
    assertEquals("preflight-ok", Files.readString(directory.resolve(".knapshots/gradle-preflight.log")))
  }

  @Test fun `failed preflight reports Gradle failure and never launches IDE`() {
    var launched = false
    val launcher = GradlePreflightLauncher(
      EditorProcessLauncher { launched = true; EditorProcessResult(0) }, directory,
      command = { listOf("/bin/sh", "-c", "printf broken; exit 3") },
    )
    val result = launcher.launch(project())
    assertEquals(3, result.exitCode)
    assertTrue(result.summary.orEmpty().contains("Gradle preflight failed"))
    assertFalse(launched)
    assertEquals("broken", Files.readString(directory.resolve(".knapshots/gradle-preflight.log")))
  }
}
