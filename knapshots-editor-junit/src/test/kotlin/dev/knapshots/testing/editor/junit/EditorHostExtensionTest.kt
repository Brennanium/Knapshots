package dev.knapshots.testing.editor.junit

import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorHostProvider
import dev.knapshots.testing.editor.EditorObservations
import dev.knapshots.testing.editor.EditorSourceHost
import dev.knapshots.testing.editor.ReferenceObservation
import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TestEditorHostProvider : EditorHostProvider {
  override fun createHost(): EditorSourceHost = EditorSourceHost {
    object : EditorObservations {
      override fun diagnostics() = emptyList<EditorDiagnostic>()
      override fun reference(expression: String) = ReferenceObservation.Resolved()
    }
  }
}

class EditorHostExtensionTest {
  @Test fun `assertion discovers project host without explicit host`() {
    assertEditor("val name = person.name") { reference("person.name").resolves() }
  }

  @Test fun `ordinary junit assertions can run with an installed host`() {
    assertEquals(2, 1 + 1)
  }
}
