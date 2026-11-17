package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorObservations
import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSourceHost
import dev.knapshots.testing.editor.ReferenceObservation
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Runs an IDE-side adapter against one materialized project and returns only after it exits. */
public fun interface EditorProcessLauncher {
  public fun launch(project: MaterializedEditorProject): EditorProcessResult
}

/** Process outcome supplied by a launcher. Logs remain in the project directory for diagnostics. */
public data class EditorProcessResult(
  val exitCode: Int,
  val summary: String? = null,
)

/** Controls whether generated projects remain available after an editor inspection. */
public enum class EditorProjectRetention {
  /** Delete all generated files after both success and failure. */
  NEVER,

  /** Delete successful projects and retain failed ones, which is the default. */
  ON_FAILURE,

  /** Retain every generated project. Useful while developing an IDE adapter. */
  ALWAYS,
}

/** An IDE launch or response failure. [projectDirectory] is retained according to the policy. */
public class EditorInspectionException(
  message: String,
  public val projectDirectory: Path,
) : IllegalStateException("$message\nEditor project: $projectDirectory")

/**
 * Implements [EditorSourceHost] using a temporary Gradle project and an external IDE launcher.
 *
 * The launcher receives the generated project and must write an `EditorInspectionResponse` to
 * its response path. It may launch any supported IDE process; this class owns neither a
 * particular IDE nor its plugin installation mechanics.
 */
public class RunnerEditorSourceHost(
  private val template: EditorProjectTemplate,
  private val launcher: EditorProcessLauncher,
  private val temporaryParent: Path? = null,
  private val retention: EditorProjectRetention = EditorProjectRetention.ON_FAILURE,
) : EditorSourceHost {
  override fun open(request: EditorRequest): EditorObservations {
    val parent = temporaryParent ?: Path.of(System.getProperty("java.io.tmpdir"))
    Files.createDirectories(parent)
    val temporaryDirectory = Files.createTempDirectory(parent, "knapshots-editor-")
    var successful = false
    try {
      val project = EditorProjectMaterializer().materialize(temporaryDirectory.resolve("project"), template, request)
      val process = launcher.launch(project)
      if (process.exitCode != 0) {
        throw EditorInspectionException(
          buildString {
            append("IDE process exited with code ${process.exitCode}.")
            process.summary?.takeIf(String::isNotBlank)?.let { append(" $it") }
          },
          project.directory,
        )
      }
      val response = try {
        EditorInspectionProtocol.readResponse(project.responsePath)
      } catch (error: IllegalArgumentException) {
        throw EditorInspectionException(error.message ?: "IDE response could not be read.", project.directory)
      }
      when (response) {
        is EditorInspectionResponse.Failed -> throw EditorInspectionException(response.reason, project.directory)
        is EditorInspectionResponse.Completed -> {
          successful = true
          return RecordedEditorObservations(response)
        }
      }
    } finally {
      if (retention == EditorProjectRetention.NEVER ||
        (retention == EditorProjectRetention.ON_FAILURE && successful)) {
        deleteRecursively(temporaryDirectory)
      }
    }
  }

  private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}

private class RecordedEditorObservations(
  private val response: EditorInspectionResponse.Completed,
) : EditorObservations {
  override fun diagnostics(): List<EditorDiagnostic> = response.diagnostics
    ?: error("The IDE did not provide diagnostics for this editor inspection.")

  override fun reference(expression: String): ReferenceObservation = response.references[expression]
    ?: ReferenceObservation.Unavailable("The IDE did not return an observation for '$expression'.")
}
