package dev.knapshots.testing.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorSourceHostTest {
  @Test fun `scoped hosts restore outer configuration after nested failure`() {
    val calls = mutableListOf<String>()
    fun host(name: String) = EditorSourceHost {
      calls += name
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertFailsWith<IllegalStateException> { assertEditor("class Example") {} }
    withEditorHost(host("outer")) {
      assertEditor("class Example") {}
      assertFailsWith<IllegalArgumentException> {
        withEditorHost(host("inner")) {
          assertEditor("class Example") {}
          throw IllegalArgumentException("test")
        }
      }
      assertEditor("class Example") {}
    }
    assertEquals(listOf("outer", "inner", "outer"), calls)
    assertFailsWith<IllegalStateException> { assertEditor("class Example") {} }
  }

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

  @Test fun `source diagnostic snapshot renders line and column`() {
    val host = EditorSourceHost {
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference", "Test.kt:1:9"))
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertEditor("val x = missing", host) {
      sourceDiagnosticsSnapshot("val x = missing\n        ^\nerror: Unresolved reference")
      reference("missing").isUnresolved()
    }
    assertFailsWith<AssertionError> { assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot("") } }
    assertFailsWith<IllegalStateException> {
      assertEditor("val x = missing", host) { sourceDiagnosticsSnapshot(""); diagnostics { error("Unresolved") } }
    }
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

  @Test fun `source assertions complete managed sessions with their result`() {
    val outcomes = mutableListOf<Boolean>()
    val host = EditorSourceHost {
      object : EditorObservationSession {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
        override fun complete(successful: Boolean) {
          outcomes += successful
        }
      }
    }

    assertEditor("class Example", host) { reference("missing").isUnresolved() }
    assertFailsWith<AssertionError> {
      assertEditor("class Example", host) { reference("missing").resolves() }
    }

    assertEquals(listOf(true, false), outcomes)
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

  @Test fun `explicit readiness prerequisites are sent separately from assertions`() {
    var opened: EditorRequest? = null
    val host = EditorSourceHost { request ->
      opened = request
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) = ReferenceObservation.Unresolved
      }
    }
    assertEditor("val item = Example.generated", host, awaitReferences = setOf("Example.ready")) {
      reference("Example.generated").isUnresolved()
    }
    assertEquals(setOf("Example.ready"), opened?.readinessReferences)
    assertEquals(setOf("Example.generated"), opened?.referenceExpressions)
  }
}
