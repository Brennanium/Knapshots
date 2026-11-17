package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.DiagnosticSeverity
import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import dev.knapshots.testing.editor.ReferenceObservation
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EditorInspectionProtocolTest {
  @Test fun `request round trip preserves source text and requested observations`(@TempDir directory: Path) {
    val request = EditorRequest(
      sources = listOf(EditorSource("src/Example.kt", "val greeting = \"héllo=world\"")),
      referenceExpressions = linkedSetOf("greeting.length", "greeting"),
      captureDiagnostics = true,
    )
    val path = directory.resolve("request.properties")

    EditorInspectionProtocol.writeRequest(request, path)

    assertEquals(request, EditorInspectionProtocol.readRequest(path))
  }

  @Test fun `response round trip preserves unavailable states and unavailable diagnostics`(@TempDir directory: Path) {
    val response: EditorInspectionResponse = EditorInspectionResponse.Completed(
      diagnostics = null,
      references = mapOf(
        "valid" to ReferenceObservation.Resolved("example.Valid"),
        "missing" to ReferenceObservation.Unresolved,
        "busy" to ReferenceObservation.Unavailable("Indexing has not finished"),
      ),
    )
    val path = directory.resolve("response.properties")

    EditorInspectionProtocol.writeResponse(response, path)

    assertEquals(response, EditorInspectionProtocol.readResponse(path))
  }

  @Test fun `response round trip preserves diagnostics and failures`(@TempDir directory: Path) {
    val completed: EditorInspectionResponse = EditorInspectionResponse.Completed(
      diagnostics = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unknown symbol", "Example.kt:2:4")),
      references = emptyMap(),
    )
    val completePath = directory.resolve("complete.properties")
    EditorInspectionProtocol.writeResponse(completed, completePath)
    assertEquals(completed, EditorInspectionProtocol.readResponse(completePath))

    val failed: EditorInspectionResponse = EditorInspectionResponse.Failed("IDE process timed out")
    val failedPath = directory.resolve("failed.properties")
    EditorInspectionProtocol.writeResponse(failed, failedPath)
    assertEquals(failed, EditorInspectionProtocol.readResponse(failedPath))
  }

  @Test fun `malformed inputs are rejected rather than treated as empty observations`(@TempDir directory: Path) {
    val path = directory.resolve("broken.properties")
    Files.writeString(path, "version=1\nkind=response\nstatus=completed\ndiagnosticsAvailable=false\ndiagnostics=1\nreferences=0\n")

    val failure = assertFailsWith<IllegalArgumentException> { EditorInspectionProtocol.readResponse(path) }
    assertEquals("An IDE response cannot include diagnostics when diagnostics are unavailable.", failure.message)
  }
}
