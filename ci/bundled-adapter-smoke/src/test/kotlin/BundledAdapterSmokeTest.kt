import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Test

class BundledAdapterSmokeTest {
  @Test fun `bundled adapter resolves references and captures diagnostics`() {
    assertEditor(
      source = """
        object Values { val number = 1 }
        val valid = Values.number
        val invalid = Values.missing
      """,
      awaitReferences = setOf("Values.number"),
    ) {
      diagnostics { error("Unresolved reference") }
      reference("Values.number").resolves()
      reference("Values.missing").isUnresolved()
    }
  }
}
