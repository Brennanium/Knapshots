package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import dev.knapshots.testing.editor.EditorObservationSession
import dev.knapshots.testing.editor.ReferenceObservation
import dev.knapshots.testing.editor.assertEditor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunnerEditorSourceHostTest {
  private val template = EditorProjectTemplate(
    settingsGradleKts = "rootProject.name = \"editor-fixture\"",
    buildGradleKts = "plugins { kotlin(\"jvm\") version \"2.4.21\" }",
  )

  private val request = EditorRequest(
    sources = listOf(EditorSource("Example.kt", "class Example")),
    referenceExpressions = setOf("Example"),
    captureDiagnostics = false,
  )

  @Test fun `deletes successful projects once their observation session completes`(@TempDir directory: Path) {
    var generatedRoot: Path? = null
    val host = RunnerEditorSourceHost(
      template = template,
      temporaryParent = directory,
      launcher = EditorProcessLauncher { project ->
        generatedRoot = project.directory.parent
        assertEquals(request, EditorInspectionProtocol.readRequest(project.requestPath))
        EditorInspectionProtocol.writeResponse(
          EditorInspectionResponse.Completed(
            diagnostics = null,
            references = mapOf("Example" to ReferenceObservation.Resolved("example.Example")),
          ),
          project.responsePath,
        )
        EditorProcessResult(exitCode = 0)
      },
    )

    val observations = host.open(request) as EditorObservationSession

    assertEquals(ReferenceObservation.Resolved("example.Example"), observations.reference("Example"))
    assertTrue(Files.exists(checkNotNull(generatedRoot)))
    observations.complete(successful = true)
    assertFalse(Files.exists(checkNotNull(generatedRoot)))
  }

  @Test fun `retains a failed project and reports the process summary`(@TempDir directory: Path) {
    var projectDirectory: Path? = null
    val host = RunnerEditorSourceHost(
      template = template,
      temporaryParent = directory,
      launcher = EditorProcessLauncher { project ->
        projectDirectory = project.directory
        EditorProcessResult(exitCode = 23, summary = "IDE startup timed out")
      },
    )

    val failure = assertFailsWith<EditorInspectionException> { host.open(request) }

    assertContains(failure.message.orEmpty(), "code 23")
    assertContains(failure.message.orEmpty(), "IDE startup timed out")
    assertEquals(projectDirectory, failure.projectDirectory)
    assertTrue(Files.isRegularFile(checkNotNull(projectDirectory).resolve(".knapshots/editor-request.properties")))
  }

  @Test fun `retains a completed project when a later source assertion fails`(@TempDir directory: Path) {
    var generatedRoot: Path? = null
    val host = RunnerEditorSourceHost(
      template = template,
      temporaryParent = directory,
      launcher = EditorProcessLauncher { project ->
        generatedRoot = project.directory.parent
        EditorInspectionProtocol.writeResponse(
          EditorInspectionResponse.Completed(
            diagnostics = null,
            references = mapOf("Example" to ReferenceObservation.Resolved("example.Example")),
          ),
          project.responsePath,
        )
        EditorProcessResult(exitCode = 0)
      },
    )

    assertFailsWith<AssertionError> {
      assertEditor("class Example", host, checkDiagnostics = false) {
        reference("Example").isUnresolved()
      }
    }

    assertTrue(Files.exists(checkNotNull(generatedRoot)))
  }

  @Test fun `failed adapter responses are not treated as unavailable observations`(@TempDir directory: Path) {
    val host = RunnerEditorSourceHost(
      template = template,
      temporaryParent = directory,
      retention = EditorProjectRetention.NEVER,
      launcher = EditorProcessLauncher { project ->
        EditorInspectionProtocol.writeResponse(
          EditorInspectionResponse.Failed("Indexing did not become ready"),
          project.responsePath,
        )
        EditorProcessResult(exitCode = 0)
      },
    )

    val failure = assertFailsWith<EditorInspectionException> { host.open(request) }

    assertContains(failure.message.orEmpty(), "Indexing did not become ready")
    assertTrue(Files.list(directory).use { !it.findAny().isPresent })
  }
}
