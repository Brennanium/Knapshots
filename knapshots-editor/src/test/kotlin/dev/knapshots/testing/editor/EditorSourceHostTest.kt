package dev.knapshots.testing.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorSourceHostTest {
  @Test fun `inline source is opened once and assertions use its observations`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      check(opened == null)
      opened = request
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) =
          if (expression == "person.name") ReferenceObservation.Resolved()
          else ReferenceObservation.Unavailable("not in file")
      }
    }

    assertEditor(
      source = """
        val name = person.name
      """,
      host = host,
      fileName = "Example.kt",
    ) { reference("person.name").resolves() }

    assertEquals(listOf(EditorSource("Example.kt", "val name = person.name")), opened?.sources)
    assertEquals(setOf("person.name"), opened?.referenceExpressions)
    assertEquals(true, opened?.captureDiagnostics)
  }

  @Test fun `hosting does not suppress unexpected editor errors`() {
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Type mismatch"))
        override fun reference(expression: String) = ReferenceObservation.Unavailable("not inspected")
      }
    }
    assertFailsWith<AssertionError> { assertEditor("val value: Int = \"wrong\"", host) {} }
  }

  @Test fun `additional sources and disabled diagnostics are sent to the host`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      opened = request
      object : EditorObservations {
        override fun diagnostics(): List<EditorDiagnostic> = error("diagnostics should not be read")
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }

    assertEditor(
      source = "class First",
      host = host,
      fileName = "First.kt",
      additionalSources = listOf(EditorSource("second/Second.kt", "  class Second")),
      checkDiagnostics = false,
    ) {
      reference("missing.member").isUnresolved()
    }

    assertEquals(
      listOf(EditorSource("First.kt", "class First"), EditorSource("second/Second.kt", "class Second")),
      opened?.sources,
    )
    assertEquals(setOf("missing.member"), opened?.referenceExpressions)
    assertEquals(false, opened?.captureDiagnostics)
  }
}
