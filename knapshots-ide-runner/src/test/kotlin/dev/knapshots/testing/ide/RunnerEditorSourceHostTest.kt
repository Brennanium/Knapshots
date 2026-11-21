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

  @Test fun `retains successful projects when explicitly requested`(@TempDir directory: Path) {
    var generatedRoot: Path? = null
    val host = RunnerEditorSourceHost(template, EditorProcessLauncher { project ->
      generatedRoot = project.directory.parent
      EditorInspectionProtocol.writeResponse(
        EditorInspectionResponse.Completed(null, mapOf("Example" to ReferenceObservation.Resolved())), project.responsePath,
      )
      EditorProcessResult(0)
    }, temporaryParent = directory, retention = EditorProjectRetention.ALWAYS)
    (host.open(request) as EditorObservationSession).complete(true)
    assertTrue(Files.exists(checkNotNull(generatedRoot)))
  }

  @Test fun `materializes projects under the canonical temporary parent`(@TempDir directory: Path) {
    val realParent = Files.createDirectories(directory.resolve("real"))
    val alias = Files.createSymbolicLink(directory.resolve("alias"), realParent)
    val host = RunnerEditorSourceHost(template, EditorProcessLauncher { project ->
      assertTrue(project.directory.startsWith(realParent.toRealPath()))
      assertEquals(project.directory, project.directory.toRealPath())
      EditorInspectionProtocol.writeResponse(
        EditorInspectionResponse.Completed(null, mapOf("Example" to ReferenceObservation.Resolved())), project.responsePath,
      )
      EditorProcessResult(0)
    }, temporaryParent = alias)
    (host.open(request) as EditorObservationSession).complete(true)
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

  @Test fun `collects protocol and diagnostic files on an assertion failure`(@TempDir directory: Path) {
    val reports = directory.resolve("reports")
    val host = RunnerEditorSourceHost(
      template = template,
      temporaryParent = directory.resolve("temporary"),
      retention = EditorProjectRetention.NEVER,
      reportDirectory = reports,
      failureReportFiles = listOf("nested-build/idea.log"),
      launcher = EditorProcessLauncher { project ->
        Files.writeString(project.directory.resolve(".knapshots/ide-process.log"), "launcher log")
        val log = project.directory.parent.resolve("nested-build/idea.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "IDE log")
        EditorInspectionProtocol.writeResponse(
          EditorInspectionResponse.Completed(null, mapOf("Example" to ReferenceObservation.Resolved())),
          project.responsePath,
        )
        EditorProcessResult(0)
      },
    )
    assertFailsWith<AssertionError> {
      assertEditor("class Example", host, checkDiagnostics = false) { reference("Example").isUnresolved() }
    }
    val files = Files.walk(reports).use { it.filter(Files::isRegularFile).toList() }
    assertTrue(files.any { it.fileName.toString() == "editor-request.properties" })
    assertTrue(files.any { it.fileName.toString() == "editor-response.properties" })
    assertTrue(files.any { it.fileName.toString() == "ide-process.log" && Files.readString(it) == "launcher log" })
    assertTrue(files.any { it.fileName.toString() == "idea.log" && Files.readString(it) == "IDE log" })
  }

  @Test fun `rejects escaping report paths`(@TempDir directory: Path) {
    assertFailsWith<IllegalArgumentException> {
      RunnerEditorSourceHost(template, EditorProcessLauncher { EditorProcessResult(0) },
        reportDirectory = directory, failureReportFiles = listOf("../outside"))
    }
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
  @Test fun `installed IDE build is read from selected installation`(@TempDir directory: Path) {
    val resources = Files.createDirectories(directory.resolve("Contents/Resources"))
    Files.writeString(resources.resolve("build.txt"), "AI-262.123.4\n")
    assertEquals("AI-262.123.4", installedIdeBuild(directory))
    val linux = Files.createDirectories(directory.resolve("linux"))
    Files.writeString(linux.resolve("build.txt"), "IU-263.9\n")
    assertEquals("IU-263.9", installedIdeBuild(linux))
    assertEquals(null, installedIdeBuild(directory.resolve("absent")))
  }

}
