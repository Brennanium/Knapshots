import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Test
class KPathsBundledAdapterTest {
  @Test fun `KPaths works using the bundled adapter`() {
    assertEditor(
      source = """
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(var name: String)
        @Pathable sealed interface Event { data class Message(val text: String) : Event }
        val namePath = Person.Paths.name
        val messagePath = Event.Paths.Message.text
      """,
      awaitReferences = setOf("Person.Paths.name", "Event.Paths.Message.text"),
    ) {
      reference("Person.Paths.name").resolves()
      reference("Event.Paths.Message.text").resolves()
    }
  }
}
