import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Test

class BundledFixtureCompilerTest {
  @Test fun `fixture compiler plugin generates an editor-visible member`() {
    assertEditor(
      source = """
        package fixture
        annotation class AddMember
        @AddMember class Example
        val model = Example()
        val value = model.addedMember()
      """,
      awaitReferences = setOf("model.addedMember"),
    ) { reference("model.addedMember").resolves() }
  }
}
